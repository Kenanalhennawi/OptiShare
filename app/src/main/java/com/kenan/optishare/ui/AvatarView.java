package com.kenan.optishare.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.kenan.optishare.settings.AppSettings;

/** Lightweight local avatar renderer. No photo, account, network request, or personal data is used. */
public final class AvatarView extends View {
    private static final int[] SKINS={0xfff6d0ad,0xffeab98f,0xffd99562,0xffb96f43,0xff865035,0xff573521};
    private static final int[] HAIR={0xff17120f,0xff3a2418,0xff6d3f20,0xffb87333,0xffd8b04a,0xff747b88};
    private static final int[] BACK={0xff4c6fff,0xff7b54e8,0xff12a886,0xffe85b75,0xffe69a24,0xff278ecf};
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int skin,hairStyle,hairColor,background;
    private boolean glasses,beard;

    public AvatarView(Context context){super(context);load(context);}
    public AvatarView(Context context, AttributeSet attrs){super(context,attrs);load(context);}
    private void load(Context context){AppSettings s=new AppSettings(context);configure(s.avatarSkin(),s.avatarHairStyle(),s.avatarHairColor(),s.avatarBackground(),s.avatarGlasses(),s.avatarBeard());}
    public void configure(int skin,int hairStyle,int hairColor,int background,boolean glasses,boolean beard){this.skin=clamp(skin,SKINS.length);this.hairStyle=clamp(hairStyle,5);this.hairColor=clamp(hairColor,HAIR.length);this.background=clamp(background,BACK.length);this.glasses=glasses;this.beard=beard;invalidate();}
    public int skin(){return skin;} public int hairStyle(){return hairStyle;} public int hairColor(){return hairColor;} public int background(){return background;} public boolean glasses(){return glasses;} public boolean beard(){return beard;}

    @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),u=Math.min(w,h)/100f,cx=w/2f;paint.setStyle(Paint.Style.FILL);
        paint.setColor(BACK[background]);c.drawCircle(cx,h/2f,49*u,paint);
        paint.setColor(0x22000000);c.drawCircle(cx+5*u,h/2f+5*u,43*u,paint);
        paint.setColor(shirt());c.drawRoundRect(new RectF(cx-32*u,72*u,cx+32*u,112*u),22*u,22*u,paint);
        paint.setColor(SKINS[skin]);c.drawRoundRect(new RectF(cx-9*u,62*u,cx+9*u,80*u),7*u,7*u,paint);c.drawCircle(cx-24*u,46*u,7*u,paint);c.drawCircle(cx+24*u,46*u,7*u,paint);c.drawOval(new RectF(cx-25*u,17*u,cx+25*u,72*u),paint);
        drawHair(c,cx,u);drawFace(c,cx,u);
    }
    private void drawHair(Canvas c,float cx,float u){paint.setColor(HAIR[hairColor]);Path p=new Path();
        if(hairStyle==0){return;} if(hairStyle==1){c.drawArc(new RectF(cx-26*u,12*u,cx+26*u,54*u),180,180,true,paint);c.drawRoundRect(new RectF(cx-26*u,28*u,cx-19*u,49*u),4*u,4*u,paint);}
        else if(hairStyle==2){for(int i=-4;i<=4;i++)c.drawCircle(cx+i*6*u,(i%2==0?16:19)*u,8*u,paint);c.drawRect(cx-25*u,18*u,cx+25*u,31*u,paint);}
        else if(hairStyle==3){p.moveTo(cx-25*u,33*u);p.lineTo(cx-20*u,10*u);p.lineTo(cx-9*u,19*u);p.lineTo(cx,7*u);p.lineTo(cx+8*u,19*u);p.lineTo(cx+22*u,10*u);p.lineTo(cx+26*u,34*u);p.close();c.drawPath(p,paint);}
        else{c.drawArc(new RectF(cx-27*u,10*u,cx+27*u,55*u),180,180,true,paint);c.drawRoundRect(new RectF(cx-28*u,25*u,cx-20*u,67*u),4*u,4*u,paint);c.drawRoundRect(new RectF(cx+20*u,25*u,cx+28*u,67*u),4*u,4*u,paint);}
    }
    private void drawFace(Canvas c,float cx,float u){paint.setStrokeWidth(2.2f*u);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(0xff2b2522);paint.setStyle(Paint.Style.FILL);c.drawCircle(cx-9*u,43*u,2.2f*u,paint);c.drawCircle(cx+9*u,43*u,2.2f*u,paint);
        if(glasses){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*u);c.drawRoundRect(new RectF(cx-18*u,37*u,cx-2*u,49*u),4*u,4*u,paint);c.drawRoundRect(new RectF(cx+2*u,37*u,cx+18*u,49*u),4*u,4*u,paint);c.drawLine(cx-2*u,42*u,cx+2*u,42*u,paint);}
        if(beard){paint.setStyle(Paint.Style.FILL);paint.setColor(HAIR[hairColor]);c.drawArc(new RectF(cx-18*u,48*u,cx+18*u,70*u),0,180,false,paint);paint.setColor(SKINS[skin]);c.drawOval(new RectF(cx-11*u,48*u,cx+11*u,62*u),paint);}
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*u);paint.setColor(0xff8f443d);c.drawArc(new RectF(cx-8*u,49*u,cx+8*u,60*u),15,150,false,paint);paint.setStyle(Paint.Style.FILL);
    }
    private int shirt(){return Color.rgb(Math.max(20,Color.red(BACK[background])-35),Math.max(30,Color.green(BACK[background])-20),Math.min(255,Color.blue(BACK[background])+25));}
    private static int clamp(int value,int length){return Math.max(0,Math.min(length-1,value));}
}
