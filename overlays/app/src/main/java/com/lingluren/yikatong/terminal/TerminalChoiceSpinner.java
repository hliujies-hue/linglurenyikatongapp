package com.lingluren.yikatong.terminal;
import android.content.Context;
import android.widget.Spinner;
/** Preserve Spinner selection callbacks while sharing the app's choice dialog. */
final class TerminalChoiceSpinner extends Spinner {
    TerminalChoiceSpinner(Context context){super(context,Spinner.MODE_DIALOG);}
    @Override public boolean performClick(){
        if(!isEnabled()||getAdapter()==null)return false;
        CharSequence[] choices=new CharSequence[getAdapter().getCount()];
        for(int i=0;i<choices.length;i++)choices[i]=String.valueOf(getAdapter().getItem(i));
        new TerminalDialogBuilder(getContext()).setTitle(getPrompt()).setSingleChoiceItems(choices,getSelectedItemPosition(),(dialog,which)->{setSelection(which);dialog.dismiss();}).setNegativeButton("取消",null).show();
        return true;
    }
}
