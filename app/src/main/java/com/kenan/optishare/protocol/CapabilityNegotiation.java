package com.kenan.optishare.protocol;

import com.kenan.optishare.security.CryptoSession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;

/** Authenticated, deterministic OSX/2 capability negotiation. */
public final class CapabilityNegotiation {
    public static final int PROTOCOL_MAJOR = 2;
    public static final int PROTOCOL_MINOR = 0;
    public static final int TRANSPORT_LAN_TCP = 1;
    public static final int TRANSPORT_P2P_TCP = 1 << 1;
    public static final int TRANSPORT_LOCAL_HOTSPOT = 1 << 2;
    public static final int AEAD_AES_256_GCM = 1;
    public static final int AEAD_CHACHA20_POLY1305 = 1 << 1;
    public static final int HASH_SHA_256 = 1;
    public static final int HASH_BLAKE3 = 1 << 1;
    public static final int FLAG_RESUME = 1;
    public static final int FLAG_PER_FILE_RETRY = 1 << 1;
    public static final int FLAG_CLIPBOARD = 1 << 2;
    public static final int FLAG_FOLDER_MANIFEST = 1 << 3;
    public static final int FLAG_ATOMIC_PUBLISH = 1 << 4;
    private static final int REQUIRED_FLAGS = FLAG_RESUME | FLAG_ATOMIC_PUBLISH;
    private static final int MIN_CHUNK = 64 * 1024;
    private static final int MAX_CHUNK = ResumableProtocol.DEFAULT_CHUNK_BYTES;
    private static final int MAX_STREAMS = 4;
    private static final int MAX_METADATA = 1024 * 1024;

    private CapabilityNegotiation() { }

    public static final class Offer {
        public final int major, minor, minimumMinor, transports, aeads, hashes;
        public final int maxChunkBytes, maxStreams, maxFiles, maxMetadataBytes, maxFolderDepth, flags;
        public Offer(int major, int minor, int minimumMinor, int transports, int aeads, int hashes,
                     int maxChunkBytes, int maxStreams, int maxFiles, int maxMetadataBytes,
                     int maxFolderDepth, int flags) {
            this.major=major;this.minor=minor;this.minimumMinor=minimumMinor;this.transports=transports;
            this.aeads=aeads;this.hashes=hashes;this.maxChunkBytes=maxChunkBytes;this.maxStreams=maxStreams;
            this.maxFiles=maxFiles;this.maxMetadataBytes=maxMetadataBytes;this.maxFolderDepth=maxFolderDepth;this.flags=flags;
        }
    }

    public static final class Selection {
        public final int major, minor, transports, aead, hash, chunkBytes, streams, maxFiles, maxMetadataBytes, maxFolderDepth, flags;
        Selection(int major,int minor,int transports,int aead,int hash,int chunkBytes,int streams,
                  int maxFiles,int maxMetadataBytes,int maxFolderDepth,int flags){this.major=major;this.minor=minor;
            this.transports=transports;this.aead=aead;this.hash=hash;this.chunkBytes=chunkBytes;this.streams=streams;
            this.maxFiles=maxFiles;this.maxMetadataBytes=maxMetadataBytes;this.maxFolderDepth=maxFolderDepth;this.flags=flags;}
    }

    public static Offer localOffer() {
        return new Offer(PROTOCOL_MAJOR,PROTOCOL_MINOR,0,
                TRANSPORT_LAN_TCP|TRANSPORT_P2P_TCP|TRANSPORT_LOCAL_HOTSPOT,
                AEAD_AES_256_GCM,HASH_SHA_256,MAX_CHUNK,2,BatchManifest.MAX_ENTRIES,
                MAX_METADATA,32,FLAG_RESUME|FLAG_PER_FILE_RETRY|FLAG_CLIPBOARD|FLAG_FOLDER_MANIFEST|FLAG_ATOMIC_PUBLISH);
    }

    public static Selection negotiate(Offer client, Offer server) throws IOException {
        validate(client);validate(server);
        if(client.major!=server.major||client.major!=PROTOCOL_MAJOR)throw new IOException("No common protocol major");
        int highest=Math.min(client.minor,server.minor);int lowest=Math.max(client.minimumMinor,server.minimumMinor);
        if(lowest>highest)throw new IOException("No common protocol minor");
        int transports=client.transports&server.transports;if(transports==0)throw new IOException("No common transport");
        int aead=prefer(client.aeads&server.aeads,AEAD_AES_256_GCM,AEAD_CHACHA20_POLY1305,"AEAD");
        int hash=prefer(client.hashes&server.hashes,HASH_SHA_256,HASH_BLAKE3,"hash");
        int flags=client.flags&server.flags;if((flags&REQUIRED_FLAGS)!=REQUIRED_FLAGS)throw new IOException("Required transfer capability missing");
        return new Selection(PROTOCOL_MAJOR,highest,transports,aead,hash,
                Math.min(client.maxChunkBytes,server.maxChunkBytes),Math.min(client.maxStreams,server.maxStreams),
                Math.min(client.maxFiles,server.maxFiles),Math.min(client.maxMetadataBytes,server.maxMetadataBytes),
                Math.min(client.maxFolderDepth,server.maxFolderDepth),flags);
    }

    public static byte[] encodeOffer(Offer offer) throws IOException { validate(offer);ByteArrayOutputStream bytes=new ByteArrayOutputStream(48);DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(offer.major);out.writeInt(offer.minor);out.writeInt(offer.minimumMinor);out.writeInt(offer.transports);out.writeInt(offer.aeads);out.writeInt(offer.hashes);
        out.writeInt(offer.maxChunkBytes);out.writeInt(offer.maxStreams);out.writeInt(offer.maxFiles);out.writeInt(offer.maxMetadataBytes);out.writeInt(offer.maxFolderDepth);out.writeInt(offer.flags);out.flush();return bytes.toByteArray();}
    public static Offer decodeOffer(byte[] payload) throws IOException {if(payload==null||payload.length!=48)throw new IOException("Invalid capability offer length");DataInputStream in=new DataInputStream(new ByteArrayInputStream(payload));
        Offer offer=new Offer(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt());validate(offer);return offer;}

    public static byte[] encodeSelection(Selection s,byte[] clientOffer,byte[] serverOffer)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream(80);DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(s.major);out.writeInt(s.minor);out.writeInt(s.transports);out.writeInt(s.aead);out.writeInt(s.hash);out.writeInt(s.chunkBytes);out.writeInt(s.streams);out.writeInt(s.maxFiles);out.writeInt(s.maxMetadataBytes);out.writeInt(s.maxFolderDepth);out.writeInt(s.flags);
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update("OSX/2-CAPABILITIES\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));digest.update(clientOffer);digest.update(serverOffer);out.write(digest.digest());}catch(java.security.GeneralSecurityException impossible){throw new IOException(impossible);}out.flush();return bytes.toByteArray();}

    public static Selection clientExchange(DataInputStream in,DataOutputStream out,CryptoSession crypto)throws Exception{byte[] local=encodeOffer(localOffer());SessionWire.writeFrame(out,crypto,SessionWire.TYPE_CAPABILITIES,local);
        SessionWire.Frame frame=SessionWire.readFrame(in,crypto);if(frame.type!=SessionWire.TYPE_CAPABILITIES)throw new IOException("Expected peer capabilities");Selection selected=negotiate(localOffer(),decodeOffer(frame.payload));byte[] confirmation=encodeSelection(selected,local,frame.payload);
        SessionWire.writeFrame(out,crypto,SessionWire.TYPE_CAPABILITIES_CONFIRM,confirmation);SessionWire.Frame ack=SessionWire.readFrame(in,crypto);if(ack.type!=SessionWire.TYPE_CAPABILITIES_SELECTED||!MessageDigest.isEqual(confirmation,ack.payload))throw new IOException("Capability transcript mismatch");return selected;}
    public static Selection serverExchange(DataInputStream in,DataOutputStream out,CryptoSession crypto)throws Exception{SessionWire.Frame frame=SessionWire.readFrame(in,crypto);if(frame.type!=SessionWire.TYPE_CAPABILITIES)throw new IOException("Expected client capabilities");byte[] local=encodeOffer(localOffer());SessionWire.writeFrame(out,crypto,SessionWire.TYPE_CAPABILITIES,local);
        Selection selected=negotiate(decodeOffer(frame.payload),localOffer());byte[] expected=encodeSelection(selected,frame.payload,local);SessionWire.Frame confirmation=SessionWire.readFrame(in,crypto);
        if(confirmation.type!=SessionWire.TYPE_CAPABILITIES_CONFIRM||!MessageDigest.isEqual(expected,confirmation.payload))throw new IOException("Capability transcript mismatch");SessionWire.writeFrame(out,crypto,SessionWire.TYPE_CAPABILITIES_SELECTED,expected);return selected;}

    private static int prefer(int common,int first,int second,String label)throws IOException{if((common&first)!=0)return first;if((common&second)!=0)return second;throw new IOException("No common "+label);}
    private static void validate(Offer o)throws IOException{if(o==null||o.major<=0||o.minor<0||o.minimumMinor<0||o.minimumMinor>o.minor)throw new IOException("Invalid protocol version range");
        if((o.transports&~7)!=0||o.transports==0||(o.aeads&~3)!=0||o.aeads==0||(o.hashes&~3)!=0||o.hashes==0)throw new IOException("Invalid capability bitset");
        if(o.maxChunkBytes<MIN_CHUNK||o.maxChunkBytes>MAX_CHUNK||o.maxStreams<1||o.maxStreams>MAX_STREAMS||o.maxFiles<1||o.maxFiles>BatchManifest.MAX_ENTRIES||o.maxMetadataBytes<1024||o.maxMetadataBytes>MAX_METADATA||o.maxFolderDepth<1||o.maxFolderDepth>64)throw new IOException("Invalid capability limit");}
}
