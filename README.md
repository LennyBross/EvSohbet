# 🏠 Ev Sohbet

Aynı Wi-Fi'daki telefonlar arasında mesajlaşma ve dosya gönderme uygulaması.
Sunucu ve internet gerekmez; telefonlar birbirini ev ağında kendiliğinden bulur.

## Özellikler
- Evdeki cihazları otomatik bulma ve çevrimiçi/çevrimdışı gösterme
- Birebir sohbet ve **Ev Grubu** sohbeti
- Dosya ve fotoğraf gönderme (birden fazla seçilebilir, fotoğraflar küçük önizlemeyle görünür)
- Gelen dosyalar **İndirilenler/EvSohbet** klasörüne kaydedilir
- Bildirimler (uygulama kapalıyken de)
- Gönderilemeyen mesaja dokununca yeniden gönderme

## APK nasıl yapılır?
1. Bilgisayara **Android Studio**'yu kur: https://developer.android.com/studio
2. Bu klasörü aç: *File → Open → EvSohbet*
3. İlk açılışta gerekli dosyaları indirmesini bekle. Bu işlem birkaç dakika sürebilir.
4. Menüden **Build → Build App Bundle(s) / APK(s) → Build APK(s)** seç.
5. Çıkan bildirimde **locate**'e tıkla. APK dosyası şurada olur:
   `app/build/outputs/apk/debug/app-debug.apk`
6. Bu dosyayı telefonlara gönder (WhatsApp, kablo, Drive…) ve kur.
   Telefon "bilinmeyen kaynak" izni isterse ver.

> Alternatif: Telefonu USB ile bağlayıp *Geliştirici seçenekleri → USB hata ayıklama*
> açıkken Android Studio'daki ▶ (Run) düğmesine basarsan uygulama doğrudan kurulur.

## Kullanım
- İlk açılışta adını yaz. Bildirim iznini ver.
- Listede çıkan **"Pil kısıtlamasını kaldır"** düğmesine bas. Bu sayede ekran kapalıyken de mesaj gelir.
- Tüm telefonlar **aynı Wi-Fi'a** bağlı olmalı.

## Sorun giderme
- **Kimse görünmüyor:** Bazı modemlerde "AP/Client isolation" (misafir ağı) açıktır ve cihazların birbirini görmesini engeller. Misafir ağı yerine ana ağı kullan.
- **Xiaomi/Huawei/Samsung:** Ayarlar'dan uygulamaya "Otomatik başlatma" ve "Arka planda çalışma" izni ver.
- Gereksinim: Android 10 veya üstü.

## Teknik
- Keşif: UDP yayını, port 45454 (3 saniyede bir)
- Mesaj/dosya: TCP, port 45455
- Kotlin + Jetpack Compose, ek kütüphane yok
