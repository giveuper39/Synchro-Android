package dev.synchro.app;

import android.os.Bundle;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

/** Portrait camera screen. The library owns permission requests and camera lifecycle. */
public final class QrScannerActivity extends CaptureActivity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    findViewById(R.id.scan_back).setOnClickListener(view -> finish());
  }

  @Override
  protected DecoratedBarcodeView initializeContent() {
    setContentView(R.layout.activity_scan);
    return findViewById(R.id.zxing_barcode_scanner);
  }
}
