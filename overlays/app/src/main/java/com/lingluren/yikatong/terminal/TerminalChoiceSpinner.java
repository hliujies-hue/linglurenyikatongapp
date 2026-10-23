package com.lingluren.yikatong.terminal;
import android.content.Context;
import androidx.appcompat.widget.AppCompatSpinner;
/** Use the standard choice dialog and its selection/accessibility handling. */
final class TerminalChoiceSpinner extends AppCompatSpinner {
    TerminalChoiceSpinner(Context context){super(context,android.widget.Spinner.MODE_DIALOG);}
}
