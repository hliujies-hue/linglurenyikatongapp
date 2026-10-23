package com.lingluren.yikatong.terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

/** Shared styling without replacing caller validation, cancellation or click handlers. */
final class TerminalDialogBuilder extends AlertDialog.Builder {
    TerminalDialogBuilder(Context context){super(context);}
    private int dp(int n){return Math.round(n*getContext().getResources().getDisplayMetrics().density);}
    @Override public AlertDialog create(){
        AlertDialog dialog=super.create();
        View decor=dialog.getWindow().getDecorView();
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View view){view.post(()->style(dialog));}
            public void onViewDetachedFromWindow(View view){}
        });
        return dialog;
    }
    @Override public AlertDialog show(){AlertDialog dialog=create();dialog.show();style(dialog);return dialog;}
    private void style(AlertDialog dialog){
        if(!dialog.isShowing())return;
        Window window=dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(Math.min(getContext().getResources().getDisplayMetrics().widthPixels-dp(40),dp(440)),ViewGroup.LayoutParams.WRAP_CONTENT);
        View decor=window.getDecorView();
        int id=getContext().getResources().getIdentifier("parentPanel","id","android");
        View panel=id==0?null:decor.findViewById(id);
        if(panel==null)panel=decor;
        GradientDrawable background=new GradientDrawable();background.setColor(Color.WHITE);background.setCornerRadius(dp(22));
        panel.setBackground(background);panel.setClipToOutline(true);
        TextView message=dialog.findViewById(android.R.id.message);
        if(message!=null){message.setTextSize(15);message.setTextColor(0xff526581);message.setLineSpacing(dp(5),1);}
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){
            Button button=dialog.getButton(which);if(button==null)continue;
            button.setAllCaps(false);button.setTextSize(14);button.setMinHeight(dp(44));button.setMinimumHeight(dp(44));button.setPadding(dp(18),0,dp(18),0);
            GradientDrawable shape=new GradientDrawable();shape.setCornerRadius(dp(12));shape.setColor(which==AlertDialog.BUTTON_POSITIVE?0xff1677ff:0xffeff4fa);button.setBackground(shape);button.setTextColor(which==AlertDialog.BUTTON_POSITIVE?Color.WHITE:0xff526581);
            if(button.getLayoutParams() instanceof ViewGroup.MarginLayoutParams){ViewGroup.MarginLayoutParams lp=(ViewGroup.MarginLayoutParams)button.getLayoutParams();lp.setMargins(dp(6),dp(8),dp(6),dp(12));button.setLayoutParams(lp);}
        }
        ListView list=dialog.getListView();if(list!=null){list.setDivider(new ColorDrawable(0xffedf2f8));list.setDividerHeight(dp(1));list.setPadding(dp(8),0,dp(8),dp(8));}
    }
}
