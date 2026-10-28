package com.example.upvccut

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private data class Part(val length: Int, val index: Int)
private data class StockBar(val parts: MutableList<Part> = mutableListOf(), var used: Int = 0)
private data class CutPlan(val bars: List<StockBar>, val stock: Int, val kerf: Int, val trim: Int) {
    val totalWaste: Int get() = bars.sumOf { stock - trim - it.used }
    val totalUsed: Int get() = bars.sumOf { it.used }
}

class OrdersDb(context: Context) : SQLiteOpenHelper(context, "upvc_orders.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT, created TEXT NOT NULL, input TEXT NOT NULL, stock INTEGER NOT NULL, kerf INTEGER NOT NULL, trim INTEGER NOT NULL, plan TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun save(input: String, stock: Int, kerf: Int, trim: Int, plan: String) {
        val v = ContentValues().apply {
            put("created", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            put("input", input); put("stock", stock); put("kerf", kerf); put("trim", trim); put("plan", plan)
        }
        writableDatabase.insert("orders", null, v)
    }
    fun all(): List<Array<String>> {
        val out = mutableListOf<Array<String>>()
        readableDatabase.rawQuery("SELECT id,created,input,stock,kerf,trim,plan FROM orders ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) out.add(Array(7) { c.getString(it) })
        }
        return out
    }
}

class MainActivity : Activity() {
    private lateinit var db: OrdersDb
    private lateinit var lengths: EditText
    private lateinit var stock: EditText
    private lateinit var kerf: EditText
    private lateinit var trim: EditText
    private lateinit var result: TextView
    private var currentPlan: CutPlan? = null
    private var currentInput = ""
    private var savedHtml = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = OrdersDb(this)
        val prefs = getSharedPreferences("upvc_access", Context.MODE_PRIVATE)
        val lastLogin = prefs.getLong("last_successful_login", 0L)
        val oneYearMs = 365L * 24 * 60 * 60 * 1000
        if (lastLogin > 0L && System.currentTimeMillis() - lastLogin < oneYearMs) showMainScreen() else showLoginScreen()
    }

    private fun acceptPassword() {
        getSharedPreferences("upvc_access", Context.MODE_PRIVATE).edit()
            .putLong("last_successful_login", System.currentTimeMillis()).apply()
        showMainScreen()
    }

    private fun showLoginScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(28, 24, 28, 24)
            layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(Color.rgb(247, 249, 250))
        }
        root.addView(text("بهینه‌ساز برش پروفیل UPVC", 24, true))
        root.addView(text("برای ورود، رمز عبور را وارد کنید", 16, false))
        val password = EditText(this).apply {
            hint = "رمز عبور"; textSize = 18f; gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(14, 10, 14, 10)
            layoutParams = LinearLayout.LayoutParams(-1, 58).apply { setMargins(0, 14, 0, 8) }
        }
        root.addView(password)
        root.addView(button("ورود", "#087F8C") {
            if (password.text.toString() == "qazwsx") acceptPassword()
            else { password.text.clear(); password.error = "رمز عبور اشتباه است" }
        })
        password.setOnEditorActionListener { _, _, _ ->
            if (password.text.toString() == "qazwsx") acceptPassword() else password.error = "رمز عبور اشتباه است"
            true
        }
        setContentView(root)
    }

    private fun showMainScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 18, 20, 20); layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(Color.rgb(247,249,250)) }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(text("بهینه‌ساز برش پروفیل UPVC", 24, true))
        body.addView(text("واحد تمام اندازه‌ها میلی‌متر است. هر خط: طول,تعداد  (مثلاً 1200,4)", 14, false))
        lengths = edit("اندازه‌ها و تعداد؛ هر خط یک مورد", "1200,4\n850,3\n1450,2", true); body.addView(lengths)
        stock = edit("طول شاخه خام", "6000"); body.addView(stock)
        kerf = edit("ضخامت تیغه برش (میلی‌متر)", "3"); body.addView(kerf)
        trim = edit("دورریز ابتدا و انتها، مجموع (میلی‌متر)", "10"); body.addView(trim)
        body.addView(button("بهینه‌سازی برش", "#087F8C") { optimize() })
        body.addView(button("ذخیره سفارش فعلی", "#2463A6") { saveOrder() })
        body.addView(button("خروجی PDF / چاپ", "#3949AB") { exportPdf() })
        body.addView(button("سفارش‌های ذخیره‌شده", "#546E7A") { showOrders() })
        result = text("نتیجه بهینه‌سازی اینجا نمایش داده می‌شود.", 15, false)
        result.setPadding(12, 18, 12, 18); body.addView(result)
        setContentView(root)
    }

    private fun text(s: String, size: Int, bold: Boolean): TextView = TextView(this).apply {
        text = s; textSize = size.toFloat(); setTextColor(Color.rgb(35,48,55)); if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        gravity = Gravity.RIGHT; setPadding(4, 9, 4, 9); textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }
    private fun edit(hintText: String, value: String, multi: Boolean = false): EditText = EditText(this).apply {
        hint = hintText; setText(value); textSize = 16f; setTextColor(Color.DKGRAY); setHintTextColor(Color.GRAY)
        setPadding(14, 10, 14, 10); background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 14f; setStroke(1, Color.LTGRAY) }
        layoutParams = LinearLayout.LayoutParams(-1, if (multi) 150 else 58).apply { setMargins(0, 5, 0, 5) }
        gravity = Gravity.RIGHT or (if (multi) Gravity.TOP else Gravity.CENTER_VERTICAL)
        if (multi) { setSingleLine(false); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        else inputType = android.text.InputType.TYPE_CLASS_NUMBER
    }
    private fun button(label: String, color: String, action: () -> Unit) = Button(this).apply {
        text = label; setTextColor(Color.WHITE); setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(color)))
        layoutParams = LinearLayout.LayoutParams(-1, 54).apply { setMargins(0, 5, 0, 5) }; setOnClickListener { action() }
    }
    private fun positive(e: EditText, name: String): Int {
        val n = e.text.toString().trim().toIntOrNull() ?: throw IllegalArgumentException("مقدار «$name» معتبر نیست")
        if (n < 0 || (name != "ضخامت تیغه" && n == 0)) throw IllegalArgumentException("مقدار «$name» باید معتبر و مثبت باشد")
        return n
    }
    private fun parseParts(raw: String): List<Part> {
        val out = mutableListOf<Part>(); var idx = 1
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val pieces = line.split(',', '،', ';', 'x', 'X', '\t').map { it.trim() }.filter { it.isNotEmpty() }
            if (pieces.size != 2) throw IllegalArgumentException("فرمت خط نادرست است: $line  |  نمونه صحیح: 1200,4")
            val len = pieces[0].toIntOrNull() ?: throw IllegalArgumentException("طول نامعتبر است: ${pieces[0]}")
            val qty = pieces[1].toIntOrNull() ?: throw IllegalArgumentException("تعداد نامعتبر است: ${pieces[1]}")
            if (len <= 0 || qty <= 0) throw IllegalArgumentException("طول و تعداد باید بیشتر از صفر باشند")
            repeat(qty) { out.add(Part(len, idx++)) }
        }
        if (out.isEmpty()) throw IllegalArgumentException("حداقل یک اندازه وارد کنید")
        return out
    }
    private fun calculate(parts: List<Part>, stockLen: Int, blade: Int, trimLen: Int): CutPlan {
        if (trimLen >= stockLen) throw IllegalArgumentException("دورریز باید از طول شاخه کمتر باشد")
        val capacity = stockLen - trimLen
        parts.forEach { if (it.length > capacity) throw IllegalArgumentException("قطعه ${it.length} میلی‌متری در طول قابل استفاده شاخه جا نمی‌شود") }
        // First-fit decreasing: practical heuristic; not a proof of globally optimal packing.
        val bars = mutableListOf<StockBar>()
        for (p in parts.sortedByDescending { it.length }) {
            var placed = false
            for (b in bars) {
                val added = p.length + if (b.parts.isEmpty()) 0 else blade
                if (b.used + added + p.length * 0 <= capacity) {
                    b.used += added; b.parts.add(p); placed = true; break
                }
            }
            if (!placed) bars.add(StockBar(mutableListOf(p), p.length))
        }
        return CutPlan(bars, stockLen, blade, trimLen)
    }
    private fun planText(p: CutPlan): String = buildString {
        append("تعداد شاخه: ${p.bars.size}\n")
        append("مجموع پرت قابل استفاده/دورریز: ${p.bars.sumOf { p.stock - p.trim - it.used }} میلی‌متر\n\n")
        p.bars.forEachIndexed { i, b ->
            append("شاخه ${i+1}: ")
            append(b.parts.joinToString(" + ") { "${it.length}" })
            append(" | طول قطعات: ${b.parts.sumOf { it.length }}")
            append(" | تعداد برش: ${b.parts.size}")
            append(" | پرت باقی‌مانده: ${p.stock - p.trim - b.used} میلی‌متر\n")
        }
    }
    private fun htmlReport(p: CutPlan, raw: String): String {
        val rows = p.bars.mapIndexed { i, b ->
            "<tr><td>${i+1}</td><td>${b.parts.joinToString(" + ") { it.length.toString() }}</td><td>${b.parts.size}</td><td>${b.parts.sumOf { it.length }}</td><td>${p.stock-p.trim-b.used}</td></tr>"
        }.joinToString("\n")
        return """<!doctype html><html lang="fa" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><style>
        body{font-family:sans-serif;direction:rtl;color:#17252a;padding:20px}h1,h2{text-align:center}table{width:100%;border-collapse:collapse;margin-top:18px}th,td{border:1px solid #777;padding:8px;text-align:center}th{background:#e5f1f2}.summary{background:#f2f6f7;padding:12px;line-height:2}.small{font-size:12px;color:#555}@media print{body{padding:0}}
        </style></head><body><h1>گزارش بهینه‌سازی برش UPVC</h1><p class="small">تاریخ: ${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())}</p><div class="summary">طول شاخه خام: ${p.stock} میلی‌متر<br>ضخامت تیغه: ${p.kerf} میلی‌متر<br>دورریز ابتدا و انتها (مجموع): ${p.trim} میلی‌متر<br>تعداد شاخه موردنیاز: ${p.bars.size}<br>پرت کل بر اساس محاسبه: ${p.bars.sumOf { p.stock-p.trim-it.used }} میلی‌متر<br>درصد استفاده از ظرفیت قابل‌استفاده: ${if (p.bars.isEmpty()) 0 else (100.0*p.bars.sumOf{it.used}/(p.bars.size*(p.stock-p.trim))).toInt()}٪</div><h2>جدول برش شاخه‌ها</h2><table><thead><tr><th>شاخه</th><th>ترتیب طول‌ها (میلی‌متر)</th><th>تعداد قطعه</th><th>مجموع طول قطعات</th><th>پرت شاخه</th></tr></thead><tbody>$rows</tbody></table><h2>اندازه‌های ورودی</h2><pre dir="ltr">${raw.replace("&","&amp;").replace("<","&lt;")}</pre><p class="small">توجه: الگوریتم چیدمان کاهشی (First Fit Decreasing) یک روش ابتکاری است و تضمین ریاضی برای بهترین جواب سراسری ندارد.</p></body></html>"""
    }
    private fun optimize() {
        try {
            val raw = lengths.text.toString(); val s = positive(stock, "طول شاخه"); val k = positive(kerf, "ضخامت تیغه"); val t = positive(trim, "دورریز")
            val p = calculate(parseParts(raw), s, k, t); currentPlan = p; currentInput = raw; savedHtml = htmlReport(p, raw)
            result.text = planText(p) + "\nنکته: الگوریتم سریع و ابتکاری است؛ برای سفارش‌های خاص می‌توان روش دقیق‌تر افزود."
        } catch (e: Exception) { Toast.makeText(this, e.message ?: "خطا در محاسبه", Toast.LENGTH_LONG).show() }
    }
    private fun saveOrder() {
        val p = currentPlan
        if (p == null) { Toast.makeText(this, "ابتدا بهینه‌سازی را اجرا کنید", Toast.LENGTH_SHORT).show(); return }
        db.save(currentInput, p.stock, p.kerf, p.trim, planText(p))
        Toast.makeText(this, "سفارش ذخیره شد", Toast.LENGTH_SHORT).show()
    }
    private fun exportPdf() {
        val p = currentPlan
        if (p == null) { Toast.makeText(this, "ابتدا بهینه‌سازی را اجرا کنید", Toast.LENGTH_SHORT).show(); return }
        val web = WebView(this)
        web.settings.defaultTextEncodingName = "UTF-8"
        web.settings.javaScriptEnabled = false
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val adapter = view.createPrintDocumentAdapter("گزارش-برش-UPVC")
                pm.print("گزارش-برش-UPVC", adapter, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).setResolution(PrintAttributes.Resolution("pdf", "PDF", 300, 300)).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build())
            }
        }
        web.loadDataWithBaseURL(null, savedHtml.ifBlank { htmlReport(p, currentInput) }, "text/HTML", "UTF-8", null)
    }
    private fun showOrders() {
        val orders = db.all()
        if (orders.isEmpty()) { Toast.makeText(this, "هنوز سفارشی ذخیره نشده است", Toast.LENGTH_SHORT).show(); return }
        val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,20,20,20); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        view.addView(text("سفارش‌های ذخیره‌شده", 22, true))
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, orders.map { "سفارش #${it[0]}  |  ${it[1]}\nشاخه ${it[3]}، تیغه ${it[4]}، دورریز ${it[5]}" })
        view.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val dialog = android.app.AlertDialog.Builder(this).setView(view).setNegativeButton("بستن", null).create()
        list.setOnItemClickListener { _, _, position, _ ->
            val o = orders[position]; lengths.setText(o[2]); stock.setText(o[3]); kerf.setText(o[4]); trim.setText(o[5]); dialog.dismiss()
            Toast.makeText(this, "ورودی سفارش بارگذاری شد؛ برای بازسازی نتیجه روی بهینه‌سازی بزنید", Toast.LENGTH_LONG).show()
        }
        dialog.show()
    }
}
