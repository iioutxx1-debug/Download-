package app.safaa.downloader;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    static String pending = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(YtPlugin.class);
        super.onCreate(savedInstanceState);
        grab(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        grab(intent);
    }

    private void grab(Intent i) {
        if (i != null && Intent.ACTION_SEND.equals(i.getAction())) {
            String t = i.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null) pending = t;
        }
    }
}
