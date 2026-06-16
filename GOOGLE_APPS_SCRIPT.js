/**
 * SI-AKSI - Sistem Informasi Aktivitas dan Kinerja Terintegrasi
 * MODUL PRESENSI WAJAH SISWA - SMKPP Negeri Bima
 * 
 * Petunjuk Penyebaran (Deployment Instructions):
 * 1. Buka Google Sheets, buat Spreadsheet baru bernama "SI-AKSI Absensi Siswa".
 * 2. Di Google Sheets Anda, buka menu Ekstensi (Extensions) -> Apps Script.
 * 3. Hapus kode bawaan, kemudian tempel seluruh script di bawah ini.
 * 4. Buka Google Drive, buat Folder baru bernama "Foto Presensi Siswa SMKPP".
 * 5. Klik kanan folder tersebut -> Bagikan (Share) -> Ubah akses menjadi "Siapa saja dengan link dapat melihat" (Anyone with link can view). Ini agar foto bukti presensi dapat diakses dari dashboard SI-AKSI.
 * 6. Salin ID Foldernya (bisa diambil dari URL bar di browser Anda: drive.google.com/drive/folders/FOLDER_ID_DISINI).
 * 7. Ganti konstanta FOLDER_ID di bawah ini dengan ID Folder Google Drive Anda agar foto login tersimpan otomatis di Drive.
 * 8. Klik tombol Simpan, lalu klik "Terapkan" (Deploy) -> "Terapkan Baru" (New Deployment).
 * 9. Pilih jenis pendeployan sebagai "Aplikasi Web" (Web App).
 * 10. Konfigurasikan hak akses: 
 *     - "Jalankan sebagai" (Execute as): Saya (Me / Akun Google Sheets Anda)
 *     - "Siapa saja yang memiliki akses" (Who has access): Siapa saja (Anyone / Anonymous) -> Sangat penting agar aplikasi Android bisa mengakses API tanpa login.
 * 11. Klik Deploy, setujui izin konfirmasi keamanan akun Google.
 * 12. Salin URL Aplikasi Web Apps Script yang dihasilkan (yang berakhiran "/exec") dan tempelkan ke dalam Tab Pengaturan (Settings) di aplikasi Android SI-AKSI Anda.
 * 
 * FITUR BARU:
 * - Autocreate Database: Spreadsheet akan secara otomatis membuat Sheet "LogAbsensiSiswa" dan "Database_Siswa" jika belum ada.
 * - Auto-insert Demo Siswa: Sheet "Database_Siswa" akan diisi 3 data siswa demonstrasi beserta koordinat wajah/embeddings 192 dimensi jika baru dibuat.
 * - Sinkronisasi dua arah: Mendukung pendaftaran siswa baru secara online dari aplikasi Android ke Spreedsheet Google.
 */

// GANTI BERIKUT DENGAN ID FOLDER GOOGLE DRIVE ANDA JIKA INGIN MENYIMPAN FOTO ABSENSI
const FOLDER_ID = "MASUKKAN_ID_FOLDER_GOOGLE_DRIVE_DISINI";

/**
 * Otomatis mendeteksi dan membuat sheet absensi & database siswa jika belum ada.
 */
function initDatabase() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) return;
  
  // 1. Buat Sheet LogAbsensiSiswa jika belum ada
  let logSheet = ss.getSheetByName("LogAbsensiSiswa");
  if (!logSheet) {
    logSheet = ss.insertSheet("LogAbsensiSiswa");
    // Format Header: Timestamp, NIS, Nama Siswa, Kelas, Jurusan, Tipe, Link Foto
    logSheet.appendRow(["Timestamp", "NIS", "Nama Siswa", "Kelas", "Jurusan", "Tipe", "Link Foto"]);
    logSheet.getRange(1, 1, 1, 7).setFontWeight("bold").setBackground("#EAEEF3");
    logSheet.setFrozenRows(1);
    SpreadsheetApp.flush();
  }
  
  // 2. Buat Sheet Database_Siswa jika belum ada
  let studentSheet = ss.getSheetByName("Database_Siswa");
  if (!studentSheet) {
    studentSheet = ss.insertSheet("Database_Siswa");
    // Format Header: nis, nama, kelas, jurusan, embedding
    studentSheet.appendRow(["nis", "nama", "kelas", "jurusan", "embedding"]);
    studentSheet.getRange(1, 1, 1, 5).setFontWeight("bold").setBackground("#E2F0D9");
    studentSheet.setFrozenRows(1);
    
    // Buat data contoh embeddings dummy berdimensi 192 agar sinkronisasi awal langsung sukses
    const emb1 = Array(192).fill(0).map((_, i) => (Math.sin(i * 0.1) * 0.3).toFixed(5)).join(",");
    const emb2 = Array(192).fill(0).map((_, i) => (Math.cos(i * 0.15) * 0.4).toFixed(5)).join(",");
    const emb3 = Array(192).fill(0).map((_, i) => (Math.sin(i * 0.25) * 0.5).toFixed(5)).join(",");

    studentSheet.appendRow([
      "9981", 
      "Khairin (Admin SI-AKSI)", 
      "XII-ATPH-A", 
      "Agribisnis Tanaman Pangan & Hortikultura", 
      emb1
    ]);
    studentSheet.appendRow([
      "3421", 
      "Muhammad Fadli", 
      "XI-ATU", 
      "Agribisnis Ternak Unggas", 
      emb2
    ]);
    studentSheet.appendRow([
      "5672", 
      "Siti Rahma", 
      "X-APHP", 
      "Agribisnis Pengolahan Hasil Pertanian", 
      emb3
    ]);
    
    SpreadsheetApp.flush();
  }
}

/**
 * Endpoint GET: Digunakan untuk mengambil daftar database wajah siswa dalam format JSON.
 */
function doGet(e) {
  try {
    // Jalankan auto-create database
    initDatabase();
    
    const action = e && e.parameter ? e.parameter.action : "";
    
    if (action === "get_students") {
      const ss = SpreadsheetApp.getActiveSpreadsheet();
      const sheet = ss.getSheetByName("Database_Siswa");
      const data = sheet.getDataRange().getValues();
      const headers = data[0].map(h => h.toString().toLowerCase().trim());
      
      const students = [];
      for (let i = 1; i < data.length; i++) {
        const row = data[i];
        if (!row[0] || row[0].toString().trim() === "") continue;
        
        const student = {};
        for (let j = 0; j < headers.length; j++) {
          const key = headers[j];
          let val = row[j];
          
          if (key === "nis" || key === "no" || key === "studentno") {
            student["studentNo"] = val.toString().trim();
          } else if (key === "nama" || key === "name" || key === "namasiswa") {
            student["name"] = val.toString().trim();
          } else if (key === "kelas" || key === "class" || key === "studentclass") {
            student["studentClass"] = val.toString().trim();
          } else if (key === "jurusan" || key === "department") {
            student["department"] = val.toString().trim();
          } else if (key === "embedding" || key === "vector" || key === "face_embedding") {
            student["embedding"] = val.toString().trim();
          }
        }
        
        // Pastikan field utama tidak kosong sebelum dikirim ke Android
        if (student["studentNo"] && student["name"] && student["embedding"]) {
          students.push(student);
        }
      }
      
      return ContentService.createTextOutput(JSON.stringify(students))
        .setMimeType(ContentService.MimeType.JSON);
    }
    
    // Respon default jika dibuka biasa di browser
    return ContentService.createTextOutput(JSON.stringify({
      "status": "success",
      "message": "Sistem Web App Presensi Wajah SI-AKSI aktif!",
      "database_status": "Ready",
      "info": "Gunakan POST untuk merekam absensi & pendaftaran, serta gunakan GET dengan parameter ?action=get_students untuk menyinkronkan data siswa."
    })).setMimeType(ContentService.MimeType.JSON);
    
  } catch (error) {
    return ContentService.createTextOutput(JSON.stringify({
      "status": "error",
      "message": "Kesalahan GET: " + error.toString()
    })).setMimeType(ContentService.MimeType.JSON);
  }
}

/**
 * Endpoint POST: Menghandle perekaman presensi (Masuk/Pulang) dan pendaftaran siswa baru secara online.
 */
function doPost(e) {
  try {
    // Pastikan database siap
    initDatabase();
    
    // Parsing input JSON dari android
    const postData = JSON.parse(e.postData.contents);
    const action = postData.action || "";
    
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    
    // KASUS 1: Registrasi/Pendaftaran Siswa Baru dengan Wajah Baru dimasukkan dari hp ke Sheets
    if (action === "register_student") {
      const studentNo = postData.no || "";
      const studentName = postData.namaSiswa || "";
      const studentClass = postData.kelas || "";
      const department = postData.jurusan || "";
      const embedding = postData.embedding || "";
      
      if (!studentNo || !studentName || !embedding) {
        return createJsonResponse("error", "gagal: NIS, namaSiswa, dan koordinat wajah (embedding) harus diisi.");
      }
      
      const sheet = ss.getSheetByName("Database_Siswa");
      const data = sheet.getDataRange().getValues();
      let foundRow = -1;
      
      // Cari jika siswa dengan NIS tersebut sudah ada sebelumnya untuk di-update
      for (let i = 1; i < data.length; i++) {
        if (data[i][0].toString().trim() === studentNo.toString().trim()) {
          foundRow = i + 1; // baris excel adalah 1-indexed
          break;
        }
      }
      
      if (foundRow !== -1) {
        // Update koordinat wajah / info jika sudah ada
        sheet.getRange(foundRow, 2).setValue(studentName);
        sheet.getRange(foundRow, 3).setValue(studentClass);
        sheet.getRange(foundRow, 4).setValue(department);
        sheet.getRange(foundRow, 5).setValue(embedding);
        return createJsonResponse("success", "Siswa " + studentName + " (NIS: " + studentNo + ") berhasil diperbarui di Google Sheets.");
      } else {
        // Daftarkan baris baru
        sheet.appendRow([studentNo, studentName, studentClass, department, embedding]);
        return createJsonResponse("success", "Siswa Baru " + studentName + " berhasil didaftarkan langsung ke Google Sheets.");
      }
    }
    
    // KASUS 2: Pendaatan Presensi Biasa (Kirim log MASUK/PULANG)
    const studentNo = postData.no || "";
    const studentName = postData.namaSiswa || "";
    const studentClass = postData.kelas || "";
    const department = postData.jurusan || "";
    const photoBase64 = postData.fotoBase64 || "";
    const logType = postData.tipe || "MASUK"; // MASUK / PULANG
    
    if (!studentNo || !studentName) {
      return createJsonResponse("error", "Data presensi tidak lengkap. NIS dan namaSiswa wajib disertakan.");
    }
    
    // Upload foto ke Google Drive
    let fileUrl = "";
    if (photoBase64 && photoBase64.trim() !== "" && FOLDER_ID && FOLDER_ID !== "MASUKKAN_ID_FOLDER_GOOGLE_DRIVE_DISINI") {
      try {
        const decodedPhoto = Utilities.base64Decode(photoBase64);
        const blob = Utilities.newBlob(decodedPhoto, MimeType.JPEG);
        
        const formattedName = studentName.replace(/\s+/g, "_");
        const formattedClass = studentClass.replace(/\s+/g, "_");
        const timestampString = Utilities.formatDate(new Date(), "GMT+7", "yyyyMMdd_HHmmss");
        blob.setName(`${logType}_${formattedClass}_${formattedName}_${timestampString}.jpg`);
        
        const folder = DriveApp.getFolderById(FOLDER_ID);
        const file = folder.createFile(blob);
        fileUrl = file.getUrl();
      } catch (err) {
        // Tulis error di link foto agar admin mengetahui folder belum dishare / ID tidak pas
        fileUrl = "[Catatan: Foto Gagal Disimpan - " + err.toString() + "]";
      }
    } else if (photoBase64 && photoBase64.trim() !== "") {
      fileUrl = "[Grup Drive Belum Dikonfigurasi / Foto Ditiadakan]";
    }
    
    const sheet = ss.getSheetByName("LogAbsensiSiswa");
    const timestamp = Utilities.formatDate(new Date(), "GMT+7", "yyyy-MM-dd HH:mm:ss");
    
    // Append baris ke Log Sheet: Timestamp, NIS, Nama Siswa, Kelas, Jurusan, Tipe, Link Foto
    sheet.appendRow([
      timestamp,
      studentNo,
      studentName,
      studentClass,
      department,
      logType,
      fileUrl
    ]);
    
    return createJsonResponse("success", "Presensi " + logType + " berhasil terkirim dan dicatat untuk " + studentName, {
      "timestamp": timestamp,
      "fileUrl": fileUrl
    });
    
  } catch (error) {
    return createJsonResponse("error", "Kesalahan Server Internal GAS: " + error.toString());
  }
}

/**
 * Fungsi utilitas untuk mempermudah respon kembalian JSON
 */
function createJsonResponse(status, message, extra = {}) {
  const response = { "status": status, "message": message };
  for (let key in extra) {
    response[key] = extra[key];
  }
  return ContentService.createTextOutput(JSON.stringify(response))
    .setMimeType(ContentService.MimeType.JSON);
}
