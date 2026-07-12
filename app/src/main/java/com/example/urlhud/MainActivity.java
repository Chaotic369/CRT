package com.example.urlhud;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        WebView webView = findViewById(R.id.webview);
        
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);

        // Bind the Javascript interface to the webview
        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidAPI");

        // Load the local HTML file
        webView.loadUrl("file:///android_asset/urlbar.html");
    }
}