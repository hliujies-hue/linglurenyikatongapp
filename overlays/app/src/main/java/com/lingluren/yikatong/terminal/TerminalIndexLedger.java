package com.lingluren.yikatong.terminal;
import java.util.HashSet;
/** Actor-confined membership of successfully applied native IDs, never an admission grant. */
public final class TerminalIndexLedger {
 public interface NativeIndex {int push(int id,byte[] feature);int delete(int id);}
 private final HashSet<Integer> ids=new HashSet<>();
 public void clear(){ids.clear();}
 public int restore(NativeIndex index,int id,byte[] feature){if(ids.contains(id))return 0;int code=index.push(id,feature);if(code==0)ids.add(id);return code;}
 public int replace(NativeIndex index,int id,byte[] feature){int code=remove(index,id);return code==0?restore(index,id,feature):code;}
 public int remove(NativeIndex index,int id){if(!ids.contains(id))return 0;int code=index.delete(id);if(code==0)ids.remove(id);return code;}
}
