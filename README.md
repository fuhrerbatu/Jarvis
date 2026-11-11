# Jarvis

Android için sesli yapay zekâ asistanı. "Jarvis, …" diye seslenirsin; Jarvis ekranı
okur, gerekirse ekran görüntüsü alır ve telefonu senin yerine kullanır.

- **"Jarvis, WhatsApp'a gir ve anneme naber yaz"** → WhatsApp'ı açar, sohbeti bulur, mesajı yazıp gönderir.
- **"Jarvis, ekrandaki İngilizce metni çevir"** → çeviriyi ekranın altında küçük bir kartta gösterir.
- **"Jarvis, ekrandaki kıyafeti bana bul"** → ekrana bakar, internette arar, mağaza ve bağlantıları kartta listeler.

Beyin olarak Claude API kullanılır (varsayılan model `claude-opus-5-5`).

## Kurulum (Poco X7 Pro / HyperOS)

1. APK'yı kur. (GitHub → **Actions** → son "APK derle" çalışması → `jarvis-apk`, ya da bilgisayarda `./gradlew assembleDebug`.)
2. Jarvis'i aç, [console.anthropic.com](https://console.anthropic.com) üzerinden aldığın **Claude API anahtarını** gir ve kaydet.
3. **Mikrofon ve bildirim iznini** ver.
4. **Erişilebilirlik servisini** aç: Ayarlar → Ek ayarlar → Erişilebilirlik → İndirilen uygulamalar → Jarvis.
   Seçenek gri ise önce *Uygulama bilgisi → sağ üst ⋮ → Kısıtlanmış ayarlara izin ver*.
5. HyperOS arka planda kapatmasın diye: Uygulama bilgisi → **Otomatik başlatma: açık**, **Pil tasarrufu: Kısıtlama yok**.

## Kullanım

- **Sesle:** herhangi bir ekranda "Jarvis, …" de. Yalnızca "Jarvis" dersen birkaç saniye komutunu bekler.
- **Düğmeyle:** ekranın sağındaki **J** düğmesine dokunup konuş (sürekli dinlemeyi kapatırsan pil için en iyisi bu).
- **Yazarak:** uygulamadaki "Komut dene" kutusundan.
- Çalışan bir işi üstteki durum çubuğundaki **✕** ile ya da J düğmesine dokunarak durdurabilirsin.

## Nasıl çalışır

| Parça | Görev |
|---|---|
| `VoiceService` | Android'in konuşma tanıyıcısıyla mikrofonu dinler, "Jarvis" kelimesini yakalar |
| `JarvisAccessibilityService` | Ekrandaki öğeleri okur, ekran görüntüsü alır, dokunur, yazar, kaydırır, uygulama açar |
| `JarvisAgent` | Komutu ve ekranı Claude'a gönderir; Claude'un istediği araçları sırayla çalıştırır, web araması yapar |
| `Overlay` | Diğer uygulamaların üzerindeki J düğmesi, durum çubuğu ve sonuç kartı |

## Bilmen gerekenler

- **Gizlilik:** Komut verdiğinde o anki ekranın metni (ve gerekirse görüntüsü) Claude API'ye gönderilir.
  Komut vermediğin sürece ekran içeriği hiçbir yere gönderilmez. Ses tanıma telefonundaki Google
  konuşma servisiyle yapılır. API anahtarı yalnızca uygulamanın özel deposunda durur.
- **Ücret:** Her komut Claude API kullanımıdır ve kendi anahtarından ücretlendirilir. Daha ucuz/hızlı
  olsun istersen uygulamadaki Model alanına örneğin `claude-sonnet-5-5` yazabilirsin.
- **Güvenlik:** Jarvis satın alma, ödeme, para transferi ve silme işlemlerini kendisi tamamlamaz; son adımı sana bırakır.
- **Sürekli dinleme** pil harcar ve bazı cihazlarda tanıyıcı her başladığında kısa bir "bip" sesi duyulabilir.
  Rahatsız ederse "Jarvis diye seslenince dinle" seçeneğini kapatıp J düğmesini kullan.
- Telefon yeniden başladıktan sonra dinlemenin başlaması için Jarvis uygulamasını bir kez açman gerekir.
- Depodaki `debug.keystore` yalnızca güncellemeler aynı imzayla kurulabilsin diye vardır; gizli bir anahtar değildir.
