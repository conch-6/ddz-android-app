package com.example.perfect;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;

import com.example.perfect.server.GameServer;

public class MainActivity extends Activity {

    private WebView webView;
    private GameServer gameServer;

    // ==================== 开关配置 ====================
    private static final boolean ENABLE_FONT_LIMIT = true;   // 字体限制注入开关
    private static final int MAX_FONT_SIZE = 18;             // 最大字体大小(px)

    private static final boolean ENABLE_RECALL_SCRIPT = false; // 撤回插件注入开关
    // ================================================

    private static final int SERVER_PORT = 6660;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // 启动游戏服务器（HTTP + WebSocket 合一，绑定 0.0.0.0:6660）
        gameServer = new GameServer(SERVER_PORT, this);
        gameServer.start();

        webView = (WebView) findViewById(R.id.webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectScripts(view);
            }
        });

        // 延迟5秒等服务器启动完成再访问
        new Handler().postDelayed(() -> {
            webView.loadUrl("http://192.168.43.51:" + SERVER_PORT);
        }, 5000);
    }

    /**
     * 统一注入脚本
     */
    private void injectScripts(WebView view) {
        StringBuilder js = new StringBuilder();
        js.append("javascript:(function() {");

        // 字体限制注入
        if (ENABLE_FONT_LIMIT) {
            js.append("var fontStyle = document.createElement('style');");
            js.append("fontStyle.type = 'text/css';");
            js.append("fontStyle.innerHTML = 'body * { font-size: ")
              .append(MAX_FONT_SIZE)
              .append("px !important; }';");
            js.append("document.head.appendChild(fontStyle);");
        }

        // 撤回插件注入
        if (ENABLE_RECALL_SCRIPT) {
            js.append("window.__RECALL_SCRIPT_INJECTED = true;");
            js.append("(function() {");
            js.append("var btn = document.getElementById('btn-recall');");
            js.append("if (btn) {");
            js.append("btn.style.display = 'block';");
            js.append("} else {");
            js.append("var observer = new MutationObserver(function() {");
            js.append("var b = document.getElementById('btn-recall');");
            js.append("if (b) {");
            js.append("b.style.display = 'block';");
            js.append("observer.disconnect();");
            js.append("}");
            js.append("});");
            js.append("observer.observe(document.body, { childList: true, subtree: true });");
            js.append("}");
            js.append("})();");
        }

        js.append("})()");

        view.loadUrl(js.toString());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 关闭服务器
        if (gameServer != null) {
            gameServer.stop();
        }
    }

    @Override
    public void onBackPressed() {
        finishAndRemoveTask();
        Process.killProcess(Process.myPid());
    }
}
