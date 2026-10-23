package com.lingluren.yikatong.terminal;
public final class TerminalDownloadRangeTest {
    public static void main(String[] args) throws Exception {
        TerminalDownloadRange resumed=TerminalDownloadRange.parse(206,100,"bytes 100-199/200","100");
        if(resumed.offset!=100||resumed.total!=200)throw new AssertionError("resume");
        TerminalDownloadRange restarted=TerminalDownloadRange.parse(200,100,null,"200");
        if(restarted.offset!=0||restarted.total!=200)throw new AssertionError("server ignored Range");
        reject(206,100,"bytes 0-199/200","200");
        reject(206,100,"bytes 100-200/200","101");
        reject(206,100,"bytes 100-199/200","99");
        reject(206,100,null,"100");
        reject(416,100,null,"0");
        reject(206,100,"bytes 100-999999999999999999999/200",null);
        if(TerminalDownloadRange.parse(200,0,null,null).total!=-1)throw new AssertionError("unknown size");
        System.out.println("PASS resumed ranges, ignored Range, malformed bounds, length mismatch, HTTP errors");
    }
    private static void reject(int status,long offset,String range,String length)throws Exception{try{TerminalDownloadRange.parse(status,offset,range,length);throw new AssertionError("accepted invalid response");}catch(java.io.IOException expected){}}
}
