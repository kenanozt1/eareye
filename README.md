# EarEye

Görme engelli bireylerin çevrelerindeki nesneleri gerçek zamanlı olarak tanıyıp sesli
şekilde bildiren Android uygulaması. Kamera görüntüsü cihaz üzerinde çalışan bir nesne
algılama modeliyle işlenir ve tespit edilen nesneler Türkçe sesli geri bildirim olarak
kullanıcıya iletilir.

Cumhuriyet Üniversitesi Bilgisayar Mühendisliği bitirme projesi (2025).

## Özellikler

- **Gerçek zamanlı nesne algılama** — CameraX ile alınan görüntü akışı, TensorFlow Lite
  üzerinde çalışan YOLO tabanlı bir modelle işlenir. Model tamamen cihaz üzerinde çalışır,
  internet bağlantısı gerektirmez.
- **Türkçe sesli bildirim** — Tespit edilen nesneler Android TextToSpeech ile seslendirilir;
  TTS motorunun kullanılamadığı durumlarda çevrimiçi bir TTS servisine düşülür.
- **Nesne takibi ve tekrar filtreleme** — Kalman filtresi tabanlı basit bir takip mekanizması
  aynı nesnenin sürekli tekrar seslendirilmesini engeller ve kararlı bildirim sağlar.
- **Acil durum desteği** — Kayıtlı acil durum kişisini arama, konumu SMS ile gönderme
  ve 112'yi doğrudan arama butonları.
- **Erişilebilir arayüz** — Büyük dokunma alanları ve sesli geri bildirim odaklı sade tasarım.

## Tanınan nesne sınıfları

Model, görme engelli bir kullanıcının şehir içinde hareket ederken karşılaşabileceği
11 sınıf üzerinde eğitilmiştir:

`atm` · `bank` · `çöp kutusu` · `araba` · `kedi` · `köpek` · `yaya yolu` ·
`yaya yolu işareti` · `kırmızı ışık` · `sarı ışık` · `yeşil ışık` · `merdiven`

## Kullanılan teknolojiler

| Alan | Teknoloji |
|---|---|
| Dil | Kotlin |
| Platform | Android (minSdk 26, targetSdk 34) |
| Kamera | CameraX 1.4.0 |
| Model çalıştırma | TensorFlow Lite 2.16.1 (+ GPU delegate) |
| Model | YOLO tabanlı özel eğitilmiş nesne algılama modeli |
| Sesli bildirim | Android TextToSpeech, yedek olarak çevrimiçi TTS API |
| Konum | Google Play Services Location |
| Ağ | OkHttp |
| Eşzamanlılık | Kotlin Coroutines |

## Teknik detaylar
- Model giriş boyutu, `.tflite` dosyasının tensör şeklinden çalışma zamanında okunur.
- `Detector.kt` çıkarım ve kutu filtreleme (NMS) işlemlerini, `OverlayView.kt` sonuçların
  ekrana çizimini, `TrackedObject.kt` nesne takibini yürütür.

## Kurulum

```bash
git clone https://github.com/kenanozt1/eareye.git
```

1. Projeyi Android Studio ile açın (Gradle sürümü otomatik indirilir).
2. Gerekli izinler ilk açılışta kullanıcıdan istenir: kamera, konum, SMS, rehber, telefon.
3. Fiziksel bir cihaz bağlayarak çalıştırın — kamera akışı gerektiği için emülatör önerilmez.

## Kullanım

1. Uygulamayı açın ve kamera butonuna dokunun.
2. Kamerayı çevreye doğrultun; tanınan nesneler ekranda kutu ile işaretlenir ve
   sesli olarak bildirilir.
3. Acil durum kişisi seçmek için "Kişiyi Ara" butonuna dokunun; kayıtlı numarayı
   silmek için aynı butona uzun basın.

## Ekip

Bu proje üç kişilik bir ekip tarafından geliştirilmiştir:

- **Kenan Öztürk** — [@kenanozt1](https://github.com/kenanozt1)
- **Mehdi Özdemir** — [@mehdiozdemir](https://github.com/mehdiozdemir)]
- **Sevgi Başar** — [@sevgibasar](https://github.com/Sevgibsr1)]

## Lisans

Bu proje MIT lisansı ile lisanslanmıştır. Ayrıntılar için [LICENSE](LICENSE) dosyasına bakın.
