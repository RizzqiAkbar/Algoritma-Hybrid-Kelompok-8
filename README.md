# Simulasi Algoritma Hybrid Cloud Task Scheduling (Kelompok 8 SOKA)

Proyek ini merupakan implementasi dan evaluasi komparatif algoritma penjadwalan komputasi awan *hybrid* berbasis **CloudSim 3.0.3**:
1. **Hybrid Max-Min + Ant Colony Optimization (ACO)** *(Kandidat Utama)*
2. **Hybrid Min-Min + Genetic Algorithm (GA)** *(Kandidat Pembanding)*

Pengujian dilakukan menggunakan dataset realistis **Google Cluster Jobs (GoCJ)** dengan variasi skala 100 hingga 1.000 tasks pada lingkungan komputasi awan heterogen (10 VM).

---

## 📌 Gambaran Umum Proyek

Dalam lingkungan komputasi awan heterogen, algoritma heuristik murni (*Max-Min* atau *Min-Min*) mengambil keputusan cepat secara deterministik namun mudah terjebak dalam ketidakseimbangan beban kerja (*load imbalance*). Sebaliknya, algoritma metaheuristik murni (*ACO* atau *GA*) memerlukan iterasi lambat karena dimulai dari inisialisasi populasi/feromon acak (*cold-start*).

Pendekatan **Hybrid (Heuristic-Metaheuristic)** menggabungkan keunggulan kedua metode:
- **Heuristik** menyediakan titik awal berkualitas tinggi (*heuristic seeding*).
- **Metaheuristik** melanjutkan pencarian global untuk mengoptimalkan alokasi beban kerja dan meminimalkan *makespan*.

---

## 📁 Struktur Berkas

```
SOKA/Hybrid/Code/
├── Hybrid.java           # Kode utama: simulasi CloudSim & kedua algoritma hybrid
├── pom.xml               # Konfigurasi Maven (Java 17, CloudSim 3.0.3, Gson, Commons-Math3)
├── Datasets/             # 10 berkas dataset GoCJ format JSON (100–1.000 task)
│   ├── GoCJ_Dataset_100.json
│   ├── ...
│   └── GoCJ_Dataset_1000.json
├── Results/              # Hasil keluaran otomatis simulasi batch CloudSim
│   ├── hasil_simulasi_hybrid.csv   # Data metrik mentah komparasi
│   └── ringkasan_simulasi.txt      # Laporan rekapitulasi komparasi
└── lib/
    └── cloudsim-3.0.3.jar          # Library inti CloudSim 3.0.3
```

---

## ⚙️ Mekanisme Algoritma Hybrid

### 1. Hybrid Max-Min + ACO (Kandidat Utama)
* **Tahap 1 (Heuristik Max-Min):**
  Menghitung waktu penyelesaian (*Completion Time* / $CT$) tiap tugas. Tugas dengan $CT$ terbesar dijadwalkan lebih awal ke VM tercepat untuk memaksimalkan paralelisasi tugas berat.
* **Tahap 2 (Guided Pheromone Seeding):**
  Rute alokasi hasil Max-Min diberi bonus jejak feromon awal:
  $$\tau_{ij}(0) = \tau_{\text{base}} + 2.0 = 3.0$$
* **Tahap 3 (Iterasi Koloni Semut ACO):**
  25 semut berjalan selama 50 siklus. Probabilitas semut memilih $VM_j$ untuk task $T_i$ dihitung dengan rumus:
  $$P_{ij} = \frac{[\tau_{ij}]^\alpha \cdot [\eta_{ij}]^\beta}{\sum_{k=1}^{m} [\tau_{ik}]^\alpha \cdot [\eta_{ik}]^\beta}, \quad \text{dengan } \eta_{ij} = \frac{1}{ET_{ij} + 10^{-6}}$$
* **Tahap 4 (Evaporasi & Elitist Update):**
  Feromon menguap sebesar $\rho = 0.2$ per iterasi, dan jalur dengan makespan terbaik global mendapat deposit tambahan:
  $$\Delta \tau = \frac{Q}{\text{Makespan}_{\text{best}}} \quad (Q = 100.0)$$

---

### 2. Hybrid Min-Min + GA (Kandidat Pembanding)
* **Tahap 1 (Heuristik Min-Min):**
  Secara deterministik memetakan tugas dengan $CT$ terkecil ke VM tercepat hingga seluruh tugas terjadwal.
* **Tahap 2 (Chromosome Seeding):**
  Jadwal hasil Min-Min disimpan sebagai **Kromosom ke-0 (Elit)**, sedangkan 29 kromosom lainnya dibangkitkan acak untuk membentuk populasi awal ($PopSize = 30$).
* **Tahap 3 (Evolusi Metaheuristik GA):**
  - **Fungsi Fitness:** $\text{Fitness} = \frac{1}{\text{Makespan}}$
  - **Seleksi:** *Tournament Selection* ($k = 3$)
  - **Crossover:** *Single-Point Crossover* ($P_c = 0.8$)
  - **Mutasi:** Mutasi acak alokasi VM ($P_m = 0.05$)
  - **Elitisme:** Solusi terbaik selalu dipertahankan ke generasi berikutnya selama 50 generasi.

---

## 🖥️ Lingkungan & Konfigurasi Simulasi

* **Simulator:** CloudSim 3.0.3 (Entity-based discrete event simulator)
* **Jumlah VM:** 10 Virtual Machines
* **Heterogenitas MIPS VM:** Tiers bertingkat (500, 1.000, 1.500, 2.000, dan 2.500 MIPS)
* **RAM / Bandwidth:** 2.048 MB / 10.000 Mbps
* **Biaya Pemrosesan:** `COST_PER_SEC = $3.0`
* **Dataset:** Google Cluster Jobs (GoCJ), 100 s.d. 1.000 tasks (15.000 – 900.000 MI)
* **Random Seed:** Dinamis menggunakan `SecureRandom.nextLong() ^ System.nanoTime()` agar kedua algoritma diuji pada spesifikasi VM yang identik.

---

## 📊 Hasil Pengujian & Rekapitulasi Data (GoCJ 100–1.000 Tasks)

Hasil eksekusi batch aktual dari berkas [`Results/ringkasan_simulasi.txt`](Results/ringkasan_simulasi.txt):

| N Tasks | Makespan GA | Makespan ACO ★ | Selisih Makespan | DoI GA | DoI ACO ★ | Util GA (%) | Util ACO ★ (%) | Cost (GA / ACO) | Algo Time (GA / ACO) |
|:-------:|:-----------:|:--------------:|:----------------:|:------:|:---------:|:-----------:|:--------------:|:---------------:|:--------------------:|
| **100** | 899,7 s | **852,8 s** | -46,9 s | 0,160 | **0,027** | 93,1% | **98,9%** | \$25.131 / \$25.297 | 19,6 ms / 60,9 ms |
| **200** | 1.704,7 s | **1.605,2 s** | -99,5 s | 0,271 | **0,011** | 94,3% | **99,6%** | \$48.221 / \$47.953 | 28,2 ms / 88,6 ms |
| **300** | 2.299,2 s | **2.248,1 s** | -51,1 s | 0,069 | **0,003** | 97,7% | **99,8%** | \$67.367 / \$67.336 | 26,3 ms / 130,2 ms |
| **400** | 4.406,1 s | **4.154,9 s** | -251,1 s | 0,207 | **0,007** | 94,3% | **99,8%** | \$124.682 / \$124.396 | 31,5 ms / 152,4 ms |
| **500** | 4.375,9 s | **4.199,2 s** | -176,8 s | 0,092 | **0,005** | 95,8% | **99,8%** | \$125.812 / \$125.741 | 36,9 ms / 170,9 ms |
| **600** | 5.623,5 s | **5.493,2 s** | -130,3 s | 0,135 | **0,003** | 96,8% | **99,9%** | \$163.310 / \$164.640 | 45,3 ms / 207,5 ms |
| **700** | 6.782,1 s | **6.485,2 s** | -296,9 s | 0,186 | **0,004** | 94,8% | **99,9%** | \$192.795 / \$194.383 | 53,3 ms / 246,8 ms |
| **800** | 6.609,1 s | **6.389,1 s** | -220,0 s | 0,239 | **0,002** | 93,7% | **99,9%** | \$185.812 / \$191.522 | 64,6 ms / 280,0 ms |
| **900** | 9.299,7 s | **8.898,3 s** | -401,5 s | 0,180 | **0,003** | 94,4% | **99,9%** | \$263.342 / \$266.631 | 68,7 ms / 314,7 ms |
| **1000** | 7.638,4 s | **7.412,9 s** | -225,5 s | 0,105 | **0,003** | 96,0% | **99,9%** | \$219.898 / \$222.191 | 92,5 ms / 354,0 ms |

---

## 📈 Kesimpulan Analisis

1. **Efisiensi Waktu (Makespan):**
   **Hybrid Max-Min + ACO** unggul di seluruh 10 skenario dataset, menghasilkan waktu penyelesaian total yang lebih singkat dibanding Min-Min + GA (penghematan mencapai 401,5 detik pada skala 900 task).
2. **Keseimbangan Beban (Degree of Imbalance):**
   ACO menghasilkan nilai DoI yang sangat mendekati 0 (**0,002 – 0,027**), membuktikan persebaran beban kerja yang merata di seluruh kapasitas VM.
3. **Pemanfaatan Resource (Utilization):**
   Tingkat utilisasi VM pada ACO mencapai **98,9% – 99,9%**, jauh lebih optimal dibanding GA (**93,1% – 97,7%**).
4. **Waktu Komputasi Algoritma:**
   Min-Min + GA memiliki waktu eksekusi algoritma lebih cepat (19,6 – 92,5 ms) dibandingkan ACO (60,9 – 354,0 ms). Namun, perbedaan waktu komputasi algoritma yang hanya sekitar ratusan milidetik ini terbayar lunas dengan efisiensi makespan cloud hingga ratusan detik.

---

## 🚀 Panduan Eksekusi

### Prasyarat
- **Java Development Kit (JDK):** Versi 17 atau lebih baru
- **Apache Maven:** Versi 3.8 atau lebih baru

### 1. Menjalankan Seluruh Dataset Sekaligus (Batch 100–1.000 Tasks)
```bash
mvn clean compile exec:java
```
Seluruh hasil pengujian akan otomatis dihitung dan disimpan di folder `Results/`:
- `Results/hasil_simulasi_hybrid.csv`
- `Results/ringkasan_simulasi.txt`

### 2. Menjalankan Dataset Tertentu
Anda dapat menentukan berkas dataset yang ingin diuji melalui argumen baris perintah:
```bash
# Menjalankan untuk dataset 500 task:
mvn -q exec:java "-Dexec.args=Datasets/GoCJ_Dataset_500.json"

# Menjalankan untuk dataset 100 task:
mvn -q exec:java "-Dexec.args=Datasets/GoCJ_Dataset_100.json"
```
