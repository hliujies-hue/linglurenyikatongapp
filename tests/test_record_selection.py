"""Execute the production record selection callback with small control doubles."""
from pathlib import Path
import tempfile, subprocess
s=(Path(__file__).resolve().parents[1]/'overlays/app/src/main/java/com/lingluren/yikatong/terminal/TerminalEntryActivity.java').read_text()
start=s.index('            public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View view,int position,long id){')
end=s.index('\n        };',start)
method=s[start:end].replace('android.widget.AdapterView<?>','Object').replace('android.view.View','Object')
source='''public class RecordSelectionTest {
 static class Choice {int selected;int getSelectedItemPosition(){return selected;}}
 class Query {boolean enabled=true;int clicks;boolean isEnabled(){return enabled;}void performClick(){clicks++;applied[2]=outcomes[outcome.selected];applied[3]=receipts[receipt.selected];appliedScenic[0]=scenicIds.get(scenic.selected);}}
 Choice outcome=new Choice(),receipt=new Choice(),scenic=new Choice();Query query=new Query();
 String[] outcomes={"","PASS","DENIED","NOT_EFFECTIVE","UNMATCHED"},receipts={"","CONFIRMED","PENDING"},applied={"","","","",""};
 java.util.List<Long> scenicIds=java.util.Arrays.asList(0L,19L);long[] appliedScenic={0};boolean[] pendingApply={false};
 __METHOD__
 void select(){onItemSelected(null,null,0,0);}
 public static void main(String[] args){RecordSelectionTest t=new RecordSelectionTest();t.select();if(t.query.clicks!=0)throw new AssertionError("initial request loop");
 for(int i=1;i<5;i++){t.outcome.selected=i;t.select();if(!t.applied[2].equals(t.outcomes[i]))throw new AssertionError("result");}
 for(int i=1;i<3;i++){t.receipt.selected=i;t.select();if(!t.applied[3].equals(t.receipts[i]))throw new AssertionError("receipt");}
 t.scenic.selected=1;t.select();if(t.appliedScenic[0]!=19)throw new AssertionError("scenic");
 t.query.enabled=false;t.outcome.selected=0;t.select();if(!t.pendingApply[0])throw new AssertionError("busy selection lost");
 t.query.enabled=true;t.query.performClick();if(!t.applied[2].isEmpty())throw new AssertionError("latest selection");
 t.scenic.selected=-1;int before=t.query.clicks;t.select();if(t.query.clicks!=before)throw new AssertionError("invalid adapter");
 System.out.println("PASS record selection: initial callback, all results/receipts, scenic, busy latest selection, invalid position");}
}'''.replace('__METHOD__',method)
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'RecordSelectionTest.java';p.write_text(source)
 subprocess.run(['javac','-d',d,str(p)],check=True);subprocess.run(['java','-cp',d,'RecordSelectionTest'],check=True)
