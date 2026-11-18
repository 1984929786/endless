package com.xundao.yongwuzhijing;

import android.os.Bundle;
import android.widget.TextView;
import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;

/**
 * 永无止境 v1.0.0 骨架
 *
 * 第一步：只验证云端构建链路能否打出 APK。
 * 后续依次接入：Cactus 引擎 -> Qwen3.5-2B -> OpenClaw 网关 -> 界面。
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        tv.setText("永无止境\n\n止于至善，行于无疆\n\n骨架构建成功 v1.0.0");
        tv.setTextSize(20f);
        tv.setTextColor(Color.parseColor("#F5F5F5"));
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(Color.parseColor("#0F1115"));

        setContentView(tv);
    }
}
