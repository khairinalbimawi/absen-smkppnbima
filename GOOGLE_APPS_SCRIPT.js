/**
 * SI-AKSI - Sistem Informasi Aktivitas dan Kinerja Terintegrasi
 * MODUL PRESENSI WAJAH SISWA - SMKPP Negeri Bima
 * 
 * Petunjuk Penyebaran (Deployment Instructions):
 * 1. Buka Google Sheets, buat Spreadsheet baru bernama "SI-AKSI Absensi Siswa".
 * 2. Ubah nama sheet pertama menjadi "LogAbsensiSiswa".
 * 3. Buat baris pertama (Header) berisi: [Timestamp, NO, Nama Siswa, Kelas, Jurusan, Link Foto].
 * 4. Buka Google Drive, buat Folder baru bernama "Foto Presensi Siswa SMKPP".
 * 5. Klik kanan folder tersebut -> Bagikan (Share) -> Ubah akses menjadi "Siapa saja dengan link dapat melihat" (Anyone with link can view). Ini agar foto bukti presensi dapat diakses dari dashboard SI-AKSI.
 * 6. Salin ID Foldernya (bisa diambil dari URL bar di browser Anda: drive.google.com/drive/folders/FOLDER_ID_DISINI).
 * 7. Di Google Sheets Anda, buka menu Ekstensi (Extensions) -> Apps Script.
 * 8. Hapus kode bawaan, kemudian tempel seluruh script di bawah ini.
 * 9. Ganti konstanta FOLDER_ID di bawah ini dengan ID Folder Google Drive Anda.
 * 10. Klik tombol "Terapkan" (Deploy) -> "Terapkan Baru" (New Deployment).
 * 11. Pilih jenis pendeployan sebagai "Aplikasi Web" (Web App).
 * 12. Konfigurasikan hak akses: 
 *     - "Jalankan sebagai" (Execute as): Saya (Me / Akun Google Sheets Anda)
 *     - "Siapa saja yang memiliki akses" (Who has access): Siapa saja (Anyone / Anonymous). (Sangat penting agar API OkHttp Android bisa mengirim data secara aman tanpa login Google sekunder).
 * 13. Klik Deploy, dan setujui izin konfirmasi keamanan akun Google.
 * 14. Salin URL Aplikasi Web Apps Script yang dihasilkan (yang berakhiran dengan "/exec") dan tempelkan ke dalam Tab Pengaturan (Settings) di aplikasi Android SI-AKSI Anda.
 */

// GANTI FORMAT DI BAWAH INI DENGAN ID FOLDER GOOGLE DRIVE AKUN ANDA
const FOLDER_ID = "MASUKKAN_ID_FOLDER_GOOGLE_DRIVE_DISINI";

function doPost(e) {
  try {
    // 1. Parsing payload JSON input dari aplikasi Android
    const postData = JSON.parse(e.postData.contents);
    
    const studentNo = postData.no || "";
    const studentName = postData.namaSiswa || "";
    const studentClass = postData.kelas || "";
    const department = postData.jurusan || "";
    const photoBase64 = postData.fotoBase64 || "";
    
    if (!studentNo || !studentName) {
      return ContentService.createTextOutput(JSON.stringify({
        "status": "error",
        "message": "Data siswa tidak lengkap (NIS / Nama Siswa kosong)."
      })).setMimeType(ContentService.MimeType.JSON);
    }
    
    // 2. Dekode Foto Base64 dan Simpan ke Google Drive
    let fileUrl = "";
    if (photoBase64 && photoBase64.trim() !== "") {
      const decodedPhoto = Utilities.base64Decode(photoBase64);
      const blob = Utilities.newBlob(decodedPhoto, MimeType.JPEG);
      
      // Format nama foto: Kelas_NamaSiswa_Timestamp.jpg
      const formattedName = studentName.replace(/\s+/g, "_");
      const formattedClass = studentClass.replace(/\s+/g, "_");
      const timestampString = Utilities.formatDate(new Date(), "GMT+8", "yyyyMMdd_HHmmss");
      blob.setName(`${formattedClass}_${formattedName}_${timestampString}.jpg`);
      
      const folder = DriveApp.getFolderById(FOLDER_ID);
      const file = folder.createFile(blob);
      fileUrl = file.getUrl();
    }
    
    // 3. Rekam Data Absensi ke Google Sheets
    const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName("LogAbsensiSiswa");
    if (!sheet) {
      return ContentService.createTextOutput(JSON.stringify({
        "status": "error",
        "message": "Gagal menemukan lembar kerja bernama 'LogAbsensiSiswa'."
      })).setMimeType(ContentService.MimeType.JSON);
    }
    
    const timestamp = Utilities.formatDate(new Date(), "GMT+8", "yyyy-MM-dd HH:mm:ss");
    
    // Isi data baris baru: [Timestamp, NO, Nama Siswa, Kelas, Jurusan, Link Foto]
    sheet.appendRow([
      timestamp,
      studentNo,
      studentName,
      studentClass,
      department,
      fileUrl
    ]);
    
    // 4. Kirim respon balik berformat JSON
    return ContentService.createTextOutput(JSON.stringify({
      "status": "success",
      "message": "Presensi berhasil terkirim dan dicatat untuk " + studentName,
      "timestamp": timestamp,
      "fileUrl": fileUrl
    })).setMimeType(ContentService.MimeType.JSON);
    
  } catch (error) {
    // Penanganan error global server GAS webapp
    return ContentService.createTextOutput(JSON.stringify({
      "status": "error",
      "message": "Kesalahan Server Internal: " + error.toString()
    })).setMimeType(ContentService.MimeType.JSON);
  }
}
