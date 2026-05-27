 
 
 

### 権限確認のタイミング

- 初回起動時
  - 目的: 現在の状態を反映
- BG→FG
  - 目的: 設定の変更を反映
- 権限要求時
  - 目的: 権限の反映
- スキャンの直前
  - 目的: API呼び出しが可能か最終確認

### スキャン前チェック→スキャンの間に起きるレースコンディションのfallback   

「事前チェック」とレースコンディション対策の「例外時フォールバック」の二段構え

```kotlin
// 1. 権限確認
val missing = missingPermissions()
if (missing.isNotEmpty()) {
  return
}

// →ここで権限が剥奪される（レースコンディション発生）

// 2. API呼び出し
try {
  bluetoothLeScanner.startScan(callback)
} catch (e: SecurityException) {
  // 💣 チェックしたのにも関わらずスキャン失敗
}
``` 




# BLE の基本登場人物

BLE には大きく2種類ある。

| 役割         | 意味     |
|------------|--------|
| Peripheral | 発信側    |
| Central    | 探索/接続側 |

BLEに接続して操作するようなアプリである場合、Android スマホは「周囲のBLE機器を探す側」なので、 `Central`

以下のような、接続先（発信側）のはPeripheral

* 心拍センサー
* スマートロック
* 温湿度センサー
* BLEタグ
* TV
* Beacon


# advertiseとは

Peripheral は、 自身の存在を周囲へ定期的に電波送信により知らせている

これを「Advertising」という。

イメージ:

```text
Peripheral 📡:
「My Sensorです ⚡⚡⚡」
「接続できます ⚡⚡⚡」
「RSSIこんな感じ ⚡⚡⚡」
```

を定期送信

## scanとは

scanは、Central 側（今回の Android アプリ）が、 「周囲の advertising を受信する」 こと

### ScanResult 

```kotlin id="fuj5g7"
override fun onScanResult(result: ScanResult)
```

`ScanResult` は、 「1つのadvertising受信結果（Peripheral が飛ばした情報）」 が入ってる。

### scanRecord 

`result.scanRecord`は、advertising packetの中身（Peripheral が送信した raw データ）

Peripheral がデバイス名を advertising packet に含めていた場合、`scanRecord?.deviceName`で取得可能

# device.name とは

`device.name`は、AndroidOS の BluetoothDevice 情報

例えば:

* 過去接続履歴
* pairing情報
* OS cache
* remote name取得結果


# なぜ fallback してる？

Peripheral によっては、 以下理由により「advertising packet に名前を含めない」 ことが普通にある

* packetサイズ制限
* 省電力
* privacy
* beacon用途

その場合`scanRecord?.deviceName`は nullになるが、Android OS 側 cache が残っている場合があるので

```kotlin id="m0i0g7"
scanRecord?.deviceName
    ?: device.name
    ?: "Unknown device"
```


###

```kotlin
/*
 * GATT接続状態が変わった時にOSから呼ばれるメソッド
 * 
 * gatt        今回の接続を表す BluetoothGatt
 * gattStatus  その状態変化が成功したか/失敗したか
 * newState    新しい接続状態
 */
override fun onConnectionStateChange(
    gatt: BluetoothGatt,
    gattStatus: Int,
    newState: Int,
)
```

- 接続成功
  ```txt
  gattStatus = GATT_SUCCESS
  newState = STATE_CONNECTED
  ```
  
- 正常切断
  ```txt
  gattStatus = GATT_SUCCESS
  newState = STATE_DISCONNECTED  
  ```

- 接続失敗・異常切断寄り
  ```txt
  gattStatus = 133
  newState = STATE_DISCONNECTED
  ```

```
connectGatt() 前に BLUETOOTH_CONNECT 権限確認しているか
接続中に再connectされないか
disconnect時に BluetoothGatt.close() まで確実に呼ばれるか
timeout後に状態とリソースが破綻しないか
callbackが遅れて返ってきた時に古いGATTを誤処理しないか
```
