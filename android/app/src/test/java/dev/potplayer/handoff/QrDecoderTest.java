package dev.potplayer.handoff;

import static org.junit.Assert.*;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.Test;

/** Decode an actual matrix made by the Python server with the camera's ZXing decoder. */
public final class QrDecoderTest {
  @Test
  public void decodesServerGeneratedQrAndConnectsBothCredentials() throws Exception {
    BufferedReader input =
        new BufferedReader(
            new InputStreamReader(
                getClass().getResourceAsStream("/pairing-qr.txt"), StandardCharsets.UTF_8));
    String payload = input.readLine();
    ArrayList<String> rows = new ArrayList<>();
    String row;
    while ((row = input.readLine()) != null) rows.add(row);
    int scale = 6, side = rows.size() * scale;
    int[] pixels = new int[side * side];
    for (int y = 0; y < side; y++)
      for (int x = 0; x < side; x++)
        pixels[y * side + x] =
            rows.get(y / scale).charAt(x / scale) == '1' ? 0xFF000000 : 0xFFFFFFFF;
    Result decoded =
        new QRCodeReader()
            .decode(
                new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(side, side, pixels))));
    assertEquals(payload, decoded.getText());
    PairingCode code = PairingCode.parse(decoded.getText());
    assertEquals("http://192.168.1.22:8765", code.server);
    assertEquals("demo_key_with_more_than_24_characters", code.token);
    assertEquals("Мой компьютер", code.name);
  }
}
