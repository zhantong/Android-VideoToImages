package com.polarxiong.videotoimages;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

public class MainActivity extends Activity implements VideoToFrames.Callback {
    private static final int REQUEST_CODE_GET_FILE_PATH = 1;
    private OutputImageFormat outputImageFormat;
    private MainActivity self = this;
    private String outputDir;

    final Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            String str = (String) msg.obj;
            updateInfo(str);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initImageFormatSpinner();

        final Button buttonFilePathInput = (Button) findViewById(R.id.button_file_path_input);
        buttonFilePathInput.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getFilePath(REQUEST_CODE_GET_FILE_PATH);
            }
        });

        final Button buttonStart = (Button) findViewById(R.id.button_start);
        buttonStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText editTextOutputFolder = (EditText) findViewById(R.id.folder_created);
                outputDir = Environment.getExternalStorageDirectory() + "/" + editTextOutputFolder.getText().toString();
                EditText editTextInputFilePath = (EditText) findViewById(R.id.file_path_input);
                String inputFilePath = editTextInputFilePath.getText().toString();
                VideoToFrames videoToFrames = new VideoToFrames();
                videoToFrames.setCallback(self);
                try {
                    videoToFrames.setSaveFrames(outputDir, outputImageFormat);
                    updateInfo("运行中...");
                    videoToFrames.decode(inputFilePath);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        });
    }

    private void initImageFormatSpinner() {
        Spinner barcodeFormatSpinner = (Spinner) findViewById(R.id.image_format);
        ArrayAdapter<OutputImageFormat> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, OutputImageFormat.values());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        barcodeFormatSpinner.setAdapter(adapter);
        barcodeFormatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                outputImageFormat = OutputImageFormat.values()[position];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }

    private void getFilePath(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(Intent.createChooser(intent, "Select a File"), requestCode);
        } else {
            new AlertDialog.Builder(this).setTitle("未找到文件管理器")
                    .setMessage("请安装文件管理器以选择文件")
                    .setPositiveButton("确定", null)
                    .show();
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        int id = 0;
        switch (requestCode) {
            case REQUEST_CODE_GET_FILE_PATH:
                id = R.id.file_path_input;
                break;
        }
        if (resultCode == Activity.RESULT_OK) {
            EditText editText = (EditText) findViewById(id);
            String curFileName = data.getData().getPath();
            editText.setText(curFileName);
        }
    }

    private void updateInfo(String info) {
        TextView textView = (TextView) findViewById(R.id.info);
        textView.setText(info);
    }

    public void onDecodeFrame(int index) {
        Message msg = handler.obtainMessage();
        msg.obj = "运行中...第" + index + "帧";
        handler.sendMessage(msg);
    }

    public void onFinishDecode() {
        Message msg = handler.obtainMessage();
        msg.obj = "完成！所有图片已存储到" + outputDir;
        handler.sendMessage(msg);
    }
}
