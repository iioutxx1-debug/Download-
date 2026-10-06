package app.safaa.downloader;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(YtPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
