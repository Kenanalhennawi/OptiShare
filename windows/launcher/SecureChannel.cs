using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace OptiShare.Windows;

internal static class SecureChannel
{
    internal static readonly byte[] Magic=Encoding.ASCII.GetBytes("OPTISHARE-PC-2\n");
    internal const int MaxRecordBytes=1_048_640;
    private static readonly byte[] Info=Encoding.ASCII.GetBytes("OptiShare-PC-v2/session");

    internal static byte[] DeriveKey(ReadOnlySpan<byte> sharedSecret,ReadOnlySpan<byte> salt)
    {
        if(sharedSecret.Length<16)throw new CryptographicException("Shared secret is too short.");
        if(salt.Length!=32)throw new CryptographicException("Session salt must be 32 bytes.");
        using var extract=new HMACSHA256(salt.ToArray());var prk=extract.ComputeHash(sharedSecret.ToArray());
        try{using var expand=new HMACSHA256(prk);var input=new byte[Info.Length+1];Info.CopyTo(input,0);input[^1]=1;return expand.ComputeHash(input);}
        finally{CryptographicOperations.ZeroMemory(prk);}
    }

    internal static byte[] Encrypt(ReadOnlySpan<byte> key,long sequence,bool clientToServer,ReadOnlySpan<byte> plaintext,ReadOnlySpan<byte> nonce=default)
    {
        if(key.Length!=32)throw new CryptographicException("AES-256 key required.");
        if(plaintext.Length>MaxRecordBytes-29)throw new CryptographicException("Secure record plaintext is too large.");
        var iv=nonce.IsEmpty?RandomNumberGenerator.GetBytes(12):nonce.ToArray();if(iv.Length!=12)throw new CryptographicException("GCM nonce must be 12 bytes.");
        var ciphertext=new byte[plaintext.Length];var tag=new byte[16];using var aes=new AesGcm(key,16);aes.Encrypt(iv,plaintext,ciphertext,tag,Aad(sequence,clientToServer));
        var record=new byte[1+iv.Length+ciphertext.Length+tag.Length];record[0]=12;iv.CopyTo(record,1);ciphertext.CopyTo(record,13);tag.CopyTo(record,13+ciphertext.Length);return record;
    }

    internal static byte[] Decrypt(ReadOnlySpan<byte> key,long sequence,bool clientToServer,ReadOnlySpan<byte> record)
    {
        if(key.Length!=32)throw new CryptographicException("AES-256 key required.");
        if(record.Length<30||record.Length>MaxRecordBytes||record[0]!=12)throw new CryptographicException("Invalid secure record.");
        var cipherLength=record.Length-13-16;if(cipherLength<1)throw new CryptographicException("Invalid secure record.");var plain=new byte[cipherLength];
        using var aes=new AesGcm(key,16);aes.Decrypt(record.Slice(1,12),record.Slice(13,cipherLength),record.Slice(13+cipherLength,16),plain,Aad(sequence,clientToServer));return plain;
    }

    internal static string SecurityCode(ReadOnlySpan<byte> clientPublicKey,ReadOnlySpan<byte> serverPublicKey,ReadOnlySpan<byte> salt)
    {
        if(clientPublicKey.IsEmpty||serverPublicKey.IsEmpty||salt.Length!=32)throw new CryptographicException("Invalid security transcript.");
        using var sha=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);sha.AppendData(Magic);Span<byte> length=stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(length,clientPublicKey.Length);sha.AppendData(length);sha.AppendData(clientPublicKey);
        BinaryPrimitives.WriteInt32BigEndian(length,serverPublicKey.Length);sha.AppendData(length);sha.AppendData(serverPublicKey);sha.AppendData(salt);
        var digest=sha.GetHashAndReset();var value=(digest[0]<<16)|(digest[1]<<8)|digest[2];return (value%1_000_000).ToString("D6");
    }

    internal static void SelfTest()
    {
        var shared=Enumerable.Range(0,32).Select(i=>(byte)i).ToArray();var salt=Enumerable.Range(32,32).Select(i=>(byte)i).ToArray();var key=DeriveKey(shared,salt);
        if(Convert.ToHexString(key).ToLowerInvariant()!="cf2407d9e2499ed91b23511130092e5e85c7a380ef8523014c0b3d47b4db1456")throw new CryptographicException("HKDF interoperability self-test failed.");
        var nonce=Enumerable.Range(0,12).Select(i=>(byte)i).ToArray();var record=Encrypt(key,0,true,Encoding.UTF8.GetBytes("OptiShare secure frame"),nonce);
        if(Convert.ToHexString(record).ToLowerInvariant()!="0c000102030405060708090a0baaa7610c5497f76554f3b997dba5295820aac345202c08125c8ad35c4d9fd38d3fa0f9ff1fa9")throw new CryptographicException("AES-GCM interoperability self-test failed.");
        if(Encoding.UTF8.GetString(Decrypt(key,0,true,record))!="OptiShare secure frame")throw new CryptographicException("Secure record self-test failed.");
        CryptographicOperations.ZeroMemory(key);
    }

    private static byte[] Aad(long sequence,bool clientToServer)=>Encoding.ASCII.GetBytes((clientToServer?"client:":"server:")+sequence);
}
