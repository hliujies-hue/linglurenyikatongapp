package com.lingluren.yikatong.terminal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Public branding assets only. Isolated from activation, maintenance and face data. */
final class TerminalBrandImages {
    private static final long DISK_BUDGET=16L*1024*1024, MAX_AGE=24L*60*60*1000;
    private static final int IMAGE_LIMIT=8*1024*1024;
    private final File directory;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2));
    private final LruCache<String,Bitmap> memory=new LruCache<String,Bitmap>(8*1024*1024){
        @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getByteCount();}
    };
    interface Completion { void complete(Bitmap bitmap,Exception error); }
    TerminalBrandImages(Context context){directory=new File(context.getCacheDir(),"terminal-brand-images-v1");}
    Bitmap cached(String url,boolean icon){return memory.get(key(url,icon));}
    Future<?> load(String url,boolean icon,Completion completion){
        worker.purge();
        return worker.submit(()->{
            try{
                interrupted();
                Bitmap image=cached(url,icon);
                if(image==null){
                    byte[] bytes=readCache(url);
                    if(bytes!=null){try{image=decode(bytes,icon);}catch(IOException invalid){removeCache(url);}}
                    if(image==null){
                        bytes=PlatformClient.brandingImage(url);
                        interrupted();
                        image=decode(bytes,icon);
                        // Cache failure must not turn a successful download into a display failure.
                        try{writeCache(url,bytes);}catch(IOException ignored){}
                    }
                    interrupted();
                    memory.put(key(url,icon),image);
                }
                interrupted();completion.complete(image,null);
            }catch(Exception error){if(!Thread.currentThread().isInterrupted())completion.complete(null,error);}
        });
    }
    void cancelQueued(){worker.purge();}
    void close(){worker.shutdownNow();memory.evictAll();}
    private static String key(String url,boolean icon){return (icon?"icon:":"background:")+url;}
    private static void interrupted() throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("页面已切换");}
    private static Bitmap decode(byte[] bytes,boolean icon) throws IOException {
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(options.outWidth<=0||options.outHeight<=0)throw new IOException("图片内容无法解码，请在后台重新上传PNG或JPEG图片");
        if((long)options.outWidth*options.outHeight>32000000L)throw new IOException("图片超过3200万像素，请在后台缩小图片尺寸");
        options.inJustDecodeBounds=false;options.inSampleSize=1;
        int edge=icon?256:1280;
        while(options.outWidth/options.inSampleSize>edge||options.outHeight/options.inSampleSize>edge)options.inSampleSize*=2;
        Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(image==null)throw new IOException("图片解码失败，请在后台重新上传PNG或JPEG图片");
        return image;
    }
    private File file(String url) throws IOException {
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder name=new StringBuilder();for(byte value:digest)name.append(String.format(java.util.Locale.US,"%02x",value&255));
            return new File(directory,name+".img");
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IOException("图片缓存摘要不可用",impossible);}
    }
    private byte[] readCache(String url){
        try{
            File file=file(url);long age=System.currentTimeMillis()-file.lastModified();
            if(!file.isFile())return null;
            if(file.length()<=0||file.length()>IMAGE_LIMIT||age<0||age>MAX_AGE){file.delete();return null;}
            try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int count,total=0;
                while((count=in.read(buffer))!=-1){total+=count;if(total>IMAGE_LIMIT)throw new IOException("图片缓存过大");out.write(buffer,0,count);}
                return out.toByteArray();
            }
        }catch(IOException ignored){return null;}
    }
    private void removeCache(String url){try{file(url).delete();}catch(IOException ignored){}}
    private void writeCache(String url,byte[] bytes) throws IOException {
        if(bytes.length>IMAGE_LIMIT)return;
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("图片缓存目录无法创建");
        File target=file(url),temp=new File(directory,target.getName()+".tmp");
        try{
            if(!trim(bytes.length))throw new IOException("图片缓存已满，无法回收旧图片");
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.flush();}
            if(!temp.renameTo(target))throw new IOException("图片缓存写入失败");
            trim(0);
        }finally{if(temp.exists())temp.delete();}
    }
    private boolean trim(long reserve){
        File[] files=directory.listFiles();if(files==null)return false;
        Arrays.sort(files,(left,right)->Long.compare(left.lastModified(),right.lastModified()));
        long size=0,now=System.currentTimeMillis();
        for(File file:files){if(file.getName().endsWith(".tmp")||now-file.lastModified()>MAX_AGE)file.delete();if(file.isFile())size+=file.length();}
        for(File file:files){if(size+reserve<=DISK_BUDGET)break;long bytes=file.length();if(file.delete())size-=bytes;}
        return size+reserve<=DISK_BUDGET;
    }
}
