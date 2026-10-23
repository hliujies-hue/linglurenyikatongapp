package com.lingluren.yikatong.terminal;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates a resumed HTTP body before any bytes are appended to the APK. */
final class TerminalDownloadRange {
    final long offset, total;
    private TerminalDownloadRange(long offset,long total){this.offset=offset;this.total=total;}
    static TerminalDownloadRange parse(int status,long requested,String range,String length) throws IOException {
        try {
            long body=length==null?-1:Long.parseLong(length);
            if(body < -1)throw new IllegalArgumentException();
            if(status==200)return new TerminalDownloadRange(0,body);
            if(status!=206)throw new IOException("安装包下载失败，HTTP "+status);
            Matcher match=Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(range==null?"":range);
            if(!match.matches())throw new IllegalArgumentException();
            long start=Long.parseLong(match.group(1)),end=Long.parseLong(match.group(2)),total=Long.parseLong(match.group(3));
            if(start!=requested||end<start||end>=total||(body>=0&&body!=end-start+1))throw new IllegalArgumentException();
            return new TerminalDownloadRange(start,total);
        } catch(IllegalArgumentException e){throw new IOException("下载服务器返回了不匹配的续传位置或文件长度",e);}
    }
}
