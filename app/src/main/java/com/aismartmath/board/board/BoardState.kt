package com.aismartmath.board.board

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.aismartmath.board.math.centerOf
import com.aismartmath.board.math.center
import com.aismartmath.board.math.dist
import com.aismartmath.board.math.dseg
import com.aismartmath.board.math.figOk
import com.aismartmath.board.math.inPoly
import com.aismartmath.board.math.pts
import com.aismartmath.board.math.ptsOf
import com.aismartmath.board.math.parseEq
import com.aismartmath.board.math.solveTri
import com.aismartmath.board.model.Fig
import com.aismartmath.board.model.GraphDef
import com.aismartmath.board.model.Pt
import com.aismartmath.board.model.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** Web: LB - a different letter set for every new shape. */
val LB = listOf("ABCD", "PQRS", "XYZW", "LMNO", "EFGH", "STUV", "JKLM")
private val POLY = setOf("tri", "quad", "rect", "rtri")
private val NEED = mapOf("pt" to 1, "tri" to 3, "quad" to 4)

/** The point on a graph whose tangent is being shown. */
class Trace(val f: Fig, val x: Double)

class Preset(val name: String, val eq: String, val a: Double, val b: Double, val c: Double)

/** Web: PRE */
val PRESETS = listOf(
    Preset("Line", "a*x+b", 1.0, 0.0, 0.0),
    Preset("Parabola", "a*x^2+b*x+c", 1.0, 0.0, 0.0),
    Preset("Cubic", "a*x^3+b*x^2+c*x", 1.0, 0.0, 0.0),
    Preset("Sine", "a*sin(b*x+c)", 1.0, 1.0, 0.0),
    Preset("1/x", "a/x", 1.0, 0.0, 0.0),
    Preset("√x", "a*sqrt(x)+b", 1.0, 0.0, 0.0),
    Preset("Circle", "x^2+y^2=a", 9.0, 0.0, 0.0),
    Preset("Ellipse", "x^2/a+y^2/b=1", 9.0, 4.0, 0.0),
    Preset("Hyperbola", "x^2/a-y^2/b=1", 4.0, 4.0, 0.0),
    Preset("y²=ax", "y^2=a*x", 4.0, 0.0, 0.0),
)

data class MenuItem(val key: String, val label: String, val icon: String)
data class MenuState(val x: Float, val y: Float, val items: List<MenuItem>, val at: Pt)

/** Which phase brings each tool to life. */
fun toolPhase(id: String): Int = when (id) {
    "pt", "seg", "tri", "rtri", "quad", "rect", "circ", "sel", "pan" -> 3
    "fx", "dv" -> 4
    "sk", "pen", "hl", "er" -> 5
    "trig", "unit" -> 6
    else -> 7
}

fun hcount(f: Fig): Int = when (f.t) {
    "pt" -> 1
    "seg", "circ", "rect", "rtri" -> 2
    "tri" -> 3
    "quad" -> 4
    else -> 0
}

class BoardState(val topic: Topic, private val scope: CoroutineScope) {
    val camera = Camera()
    var tool by mutableStateOf(topic.toolIds.first())
    val shapes = mutableStateListOf<Fig>()
    val pend = mutableStateListOf<Pt>()

    var cursor by mutableStateOf<Pt?>(null)
    var draft by mutableStateOf<Fig?>(null)
    var msg by mutableStateOf("")

    var clock by mutableLongStateOf(0L)
    var animTick by mutableIntStateOf(0)

    var sel by mutableStateOf<Fig?>(null)
    var editing by mutableStateOf<Fig?>(null)
    var mv by mutableStateOf<Fig?>(null)
    var rs by mutableStateOf<Fig?>(null)
    var detailsOn by mutableStateOf(false)
    var clip by mutableStateOf<Fig?>(null)
    var menu by mutableStateOf<MenuState?>(null)
    var toastMsg by mutableStateOf<String?>(null)

    // ---------- graphs ----------
    var eq by mutableStateOf("a*x+b")
    var gpA by mutableDoubleStateOf(1.0)
    var gpB by mutableDoubleStateOf(0.0)
    var gpC by mutableDoubleStateOf(0.0)
    var animA by mutableStateOf(false)
    var trace by mutableStateOf<Trace?>(null)

    // ---------- Solve: pen, highlighter, eraser, sketch ----------
    val skP = mutableStateListOf<Offset>()
    var penAt by mutableStateOf<Offset?>(null)
    var penCol by mutableStateOf(Color(0xFFFBBF24))
    var penW by mutableStateOf(4f)
    var ghost by mutableStateOf<List<Pt>>(emptyList())
    var ghostAt by mutableLongStateOf(0L)

    var holdFired = false
    private var lbN = 0
    private var hintShown = false
    private var toastJob: Job? = null
    private var holdJob: Job? = null
    private var tapJob: Job? = null
    private var tapCount = 0
    private var tapFig: Fig? = null
    private var tapAt = Offset.Zero
    private var tapWorld = Pt(0.0, 0.0)
    private var mvO: List<Pt> = emptyList()
    private var mvS = Pt(0.0, 0.0)
    private var rsO: List<Pt> = emptyList()
    private var rsC = Pt(0.0, 0.0)
    private var rsD = 1.0

    // ---------- tool kinds ----------
    val isTapTool: Boolean get() = tool in NEED
    val isDragTool: Boolean get() = tool == "seg" || tool == "rect" || tool == "rtri" || tool == "circ"
    val drawsWithFinger: Boolean get() = isTapTool || isDragTool
    val need: Int get() = NEED[tool] ?: 0

    val isGraphTool: Boolean get() = tool == "fx" || tool == "dv"
    val isFreeTool: Boolean get() = tool == "sk" || tool == "pen" || tool == "hl" || tool == "er"
    val isPenTool: Boolean get() = tool == "pen" || tool == "hl"

    fun beginFree(pos: Offset) {
        skP.clear()
        skP.add(pos)
        penAt = pos
        if (tool == "er") erase(rawWorld(pos))
    }

    fun moveFree(pos: Offset, minStep: Float) {
        val last = skP.lastOrNull()
        if (last == null || (pos - last).getDistance() >= minStep) skP.add(pos)
        penAt = pos
        if (tool == "er") erase(rawWorld(pos))
    }

    fun cancelFree() {
        skP.clear()
        penAt = null
    }

    /** Web: erase - remove every ink stroke the eraser touches. */
    fun erase(p: Pt) {
        val tol = 14.0 * camera.density / camera.u
        for (s in shapes.toList()) {
            if (s.t != "ink" || s.dieAt != 0L) continue
            val q = s.p
            if (q.indices.any { k -> dseg(p, q[k], q.getOrElse(k + 1) { q[k] }) < tol }) remove(s)
        }
    }

    /** The finger lifted after a free-hand stroke: save ink, or recognise a sketch. */
    fun finishFree() {
        val sp = skP.toList()
        cancelFree()
        if (sp.isEmpty()) return
        if (tool == "pen" || tool == "hl") {
            val born = System.nanoTime()
            val raw = if (sp.size < 2) listOf(sp[0], sp[0]) else sp
            val f = Fig("ink", raw.map { rawWorld(it) }, born)
            f.col = penCol
            f.inkW = penW.toDouble()
            f.hl = tool == "hl"
            shapes.add(f)
            bump(born)
            return
        }
        if (tool == "er") return
        val res = recognize(sp, this)
        if (res == null) {
            showToast("Couldn't recognise that — try a clearer shape")
            return
        }
        msg = ""
        ghost = sp.map { rawWorld(it) }
        ghostAt = System.nanoTime()
        addFig(res.first, res.second)
        if (msg.isEmpty()) {
            showToast("Looks like: ${FigNames[res.first] ?: res.first} — adjust it below")
            val ns = shapes.lastOrNull { it.dieAt == 0L }
            selectTool("sel")
            if (ns != null) openEdit(ns)
        }
    }

    fun lastFx(): Fig? = shapes.lastOrNull { it.t == "fx" && it.dieAt == 0L && it.gd?.dv == 0 }

    /** Web: opening Graph / Derivative with no graph yet plots the Line preset. */
    fun start() {
        if (isGraphTool && lastFx() == null) preset(0)
    }

    fun plot() {
        val src = eq.trim()
        val r = parseEq(src)
        if (r == null) {
            msg = "Could not read that. Try: 2x^2 - 3x + 1 , x^2+y^2=25 , a*sin(b*x)"
            return
        }
        msg = ""
        val born = System.nanoTime()
        val f = Fig("fx", emptyList(), born)
        f.gd = GraphDef(src, r.fn, r.imp)
        f.ga = gpA
        f.gb = gpB
        f.gc = gpC
        f.col = GraphColors[shapes.count { it.t == "fx" } % GraphColors.size]
        shapes.add(f)
        bump(born)
    }

    fun preset(i: Int) {
        val p = PRESETS[i]
        eq = p.eq
        gpA = p.a
        gpB = p.b
        gpC = p.c
        plot()
    }

    /** Web: addDv - add the f' or f'' curve of the last graph. */
    fun addDv(n: Int) {
        val b = lastFx() ?: return
        val bg = b.gd ?: return
        if (bg.imp) {
            showToast("Derivative needs a graph like y = f(x)")
            return
        }
        val born = System.nanoTime()
        val f = Fig("fx", emptyList(), born)
        f.gd = GraphDef(bg.src, null, false, n, b)
        f.col = if (n == 1) Color(0xFFF472B6) else Color(0xFF34D399)
        shapes.add(f)
        bump(born)
    }

    /** The a / b / c sliders of the graph panel. */
    fun setParam(k: String, v: Double) {
        when (k) {
            "a" -> gpA = v
            "b" -> gpB = v
            else -> gpC = v
        }
        if (isGraphTool) lastFx()?.let {
            when (k) {
                "a" -> it.ga = v
                "b" -> it.gb = v
                else -> it.gc = v
            }
        }
    }

    /** Graph / Derivative tool: tap to put the tangent on the last graph. */
    fun onGraphTap(px: Offset) {
        val g = lastFx() ?: return
        if (g.gd?.imp == true) return
        trace = Trace(g, rawWorld(px).x)
    }

    /** Web: act() - the figure whose description the info bar shows. */
    fun activeFig(): Fig? =
        editing ?: mv ?: if (isGraphTool) lastFx() else shapes.lastOrNull { it.dieAt == 0L && it.t != "ink" }

    fun selectTool(id: String) {
        tool = id
        trace = null
        animA = false
        cancelFree()
        pend.clear()
        cursor = null
        draft = null
        msg = ""
        mv = null
        rs = null
        closeEdit()
        hideMenu()
        start()
    }

    fun initialOy(): Double =
        if (tool in setOf("fx", "dv", "trig", "unit")) -70.0 * camera.density else 0.0

    // ---------- coordinates ----------
    fun rawWorld(p: Offset): Pt = Pt(camera.wx(p.x), camera.wy(p.y))
    fun snapPt(w: Pt): Pt {
        val q = camera.snapStep
        return Pt(floor(w.x / q + 0.5) * q, floor(w.y / q + 0.5) * q)
    }
    fun snap(p: Offset): Pt = snapPt(rawWorld(p))

    // ---------- animation clock ----------
    fun bump(t: Long = System.nanoTime()) {
        clock = t
        animTick++
    }

    fun showToast(m: String) {
        toastMsg = m
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(2200)
            toastMsg = null
        }
    }

    // ---------- adding / removing ----------
    fun addFig(t: String, p: List<Pt>) {
        if (!figOk(t, p)) {
            msg = "That did not make a shape — try again"
            return
        }
        msg = ""
        val born = System.nanoTime()
        val nm = if (t in POLY) LB[lbN++ % LB.size] else null
        shapes.add(Fig(t, p, born, nm))
        bump(born)
        if (!hintShown && t != "pt") {
            hintShown = true
            showToast("Tip: use 👆 Select to edit, copy or move figures")
        }
    }

    /** Called when a finger lifts after pointing with a tap tool (Point / Triangle / Quadrilateral). */
    fun placeTapPoint(p: Pt) {
        if (tool == "pt") {
            addFig("pt", listOf(p))
        } else {
            pend.add(p)
            if (pend.size == need) {
                addFig(tool, pend.toList())
                pend.clear()
            }
        }
    }

    fun remove(f: Fig) {
        if (f.dieAt != 0L) return
        val t = System.nanoTime()
        f.dieAt = t
        bump(t)
        if (editing === f) closeEdit()
        if (sel === f) sel = null
        if (mv === f) mv = null
        if (rs === f) rs = null
        if (trace?.f === f) trace = null
        scope.launch {
            delay(260)
            shapes.remove(f)
        }
    }

    fun undo() {
        if (pend.isNotEmpty()) {
            pend.removeAt(pend.lastIndex)
            return
        }
        shapes.lastOrNull { it.dieAt == 0L }?.let { remove(it) }
    }

    fun clear() {
        pend.clear()
        closeEdit()
        sel = null
        mv = null
        rs = null
        hideMenu()
        shapes.filter { it.dieAt == 0L }.forEach { remove(it) }
    }

    // ---------- hit testing ----------
    fun hit(w: Pt): Fig? {
        val tol = 16.0 * camera.density / camera.u
        for (i in shapes.indices.reversed()) {
            val s = shapes[i]
            if (s.dieAt != 0L || s.t == "ink") continue
            val q = pts(s)
            val h = when (s.t) {
                "pt" -> dist(w, q[0]) < tol * 1.4
                "seg" -> dseg(w, q[0], q[1]) < tol
                "circ" -> dist(q[0], w) <= dist(q[0], q[1]) + tol
                "fx" -> hitGraph(s, w)
                else -> inPoly(w, q) || q.indices.any { k -> dseg(w, q[k], q[(k + 1) % q.size]) < tol }
            }
            if (h) return s
        }
        return null
    }

    private fun hitGraph(s: Fig, p: Pt): Boolean {
        val g = s.gd ?: return false
        val tol = 18.0 * camera.density
        return if (g.imp) {
            val e = 1e-4
            val v = s.vAt(p.x, p.y)
            val gx = (s.vAt(p.x + e, p.y) - s.vAt(p.x - e, p.y)) / 2 / e
            val gy = (s.vAt(p.x, p.y + e) - s.vAt(p.x, p.y - e)) / 2 / e
            val gl = hypot(gx, gy).let { if (it == 0.0) 1.0 else it }
            v.isFinite() && abs(v) / gl * camera.u < tol
        } else {
            val y = s.yAt(p.x)
            y.isFinite() && abs(y - p.y) * camera.u < tol
        }
    }

    fun findHandle(px: Offset): Int {
        val e = editing ?: return -1
        if (!shapes.contains(e)) return -1
        var best = 28f * camera.density.toFloat()
        var idx = -1
        for (i in 0 until hcount(e)) {
            val q = e.p[i]
            val d = hypot(camera.sx(q.x) - px.x, camera.sy(q.y) - px.y)
            if (d < best) {
                best = d
                idx = i
            }
        }
        return idx
    }

    // ---------- editing ----------
    fun openEdit(f: Fig) {
        editing = f
        sel = f
        f.base = null
        f.sz = 1.0
        f.rot = 0.0
    }

    fun closeEdit(clearSel: Boolean = true) {
        editing = null
        detailsOn = false
        if (clearSel) {
            sel = null
            trace = null
        }
    }

    fun dragHandle(i: Int, px: Offset) {
        val f = editing ?: return
        val np = f.p.toMutableList()
        np[i] = snap(px)
        if (figOk(f.t, np)) {
            f.p = np
            f.base = null
            f.sz = 1.0
            f.rot = 0.0
        }
    }

    fun beginMove(f: Fig, px: Offset) {
        mvO = f.p
        mvS = rawWorld(px)
    }

    fun dragMove(f: Fig, px: Offset) {
        val q = camera.snapStep
        val w = rawWorld(px)
        val dx = floor((w.x - mvS.x) / q + 0.5) * q
        val dy = floor((w.y - mvS.y) / q + 0.5) * q
        f.p = mvO.map { Pt(it.x + dx, it.y + dy) }
        f.base = null
    }

    fun beginResize(f: Fig, px: Offset) {
        rsO = f.p
        rsC = centerOf(f.t, f.p)
        rsD = maxOf(dist(rawWorld(px), rsC), 1e-9)
    }

    fun dragResize(f: Fig, px: Offset) {
        val k = dist(rawWorld(px), rsC) / rsD
        if (k < 0.05 || k > 50) return
        val np = rsO.map { Pt(rsC.x + (it.x - rsC.x) * k, rsC.y + (it.y - rsC.y) * k) }
        if (figOk(f.t, np)) {
            f.p = np
            f.base = null
        }
    }

    /** Web: editField - a number box or slider in the edit panel changed. */
    fun editField(f: Fig, k: String, v: Double) {
        if (!v.isFinite()) return
        if (f.t == "fx") {
            when (k) {
                "a" -> { f.ga = v; gpA = v }
                "b" -> { f.gb = v; gpB = v }
                "c" -> { f.gc = v; gpC = v }
            }
            return
        }
        if (k == "size" || k == "rot") {
            if (k == "rot" && (f.t == "rect" || f.t == "rtri")) {
                f.p = pts(f)
                f.t = if (f.t == "rect") "quad" else "tri"
                f.base = null
                showToast("Now a free shape, so it can rotate")
            }
            val o = f.base ?: f.p.also { f.base = it }
            if (k == "size") f.sz = v / 100 else f.rot = v
            val c = centerOf(f.t, o)
            val r = f.sz
            val th = f.rot * PI / 180
            val co = cos(th)
            val si = sin(th)
            f.p = o.map { q ->
                val dx = (q.x - c.x) * r
                val dy = (q.y - c.y) * r
                Pt(c.x + dx * co - dy * si, c.y + dx * si + dy * co)
            }
            return
        }
        if (v <= 0 && k != "x" && k != "y" && k != "ang") return
        val np = f.p.toMutableList()
        when (f.t) {
            "pt" -> np[0] = if (k == "x") Pt(v, np[0].y) else Pt(np[0].x, v)
            "seg" -> {
                val a = np[0]
                val b = np[1]
                var l = dist(a, b)
                var t = atan2(b.y - a.y, b.x - a.x)
                if (k == "len") l = v else t = v * PI / 180
                np[1] = Pt(a.x + l * cos(t), a.y + l * sin(t))
            }
            "circ" -> {
                val a = np[0]
                val b = np[1]
                val t = atan2(b.y - a.y, b.x - a.x)
                np[1] = Pt(a.x + v * cos(t), a.y + v * sin(t))
            }
            "rect", "rtri" -> {
                val a = np[0]
                val b = np[1]
                val gx = if (b.x - a.x < 0) -1.0 else 1.0
                val gy = if (b.y - a.y < 0) -1.0 else 1.0
                np[1] = if (k == "w") Pt(a.x + gx * v, b.y) else Pt(b.x, a.y + gy * v)
            }
            "tri" -> {
                val r = solveTri(np, k, v)
                if (r == null) {
                    showToast("Those sides cannot make a triangle")
                    return
                }
                r.forEachIndexed { i, q -> np[i] = q }
            }
            else -> return
        }
        if (figOk(f.t, np)) {
            f.p = np
            f.base = null
            f.sz = 1.0
            f.rot = 0.0
        }
    }

    // ---------- select tool: multi-tap ----------
    fun onSelTap(px: Offset) {
        val w = rawWorld(px)
        val h = hit(w)
        if (h == null) {
            mv = null
            rs = null
            closeEdit()
            sel = null
            tapCount = 0
            tapFig = null
            return
        }
        if (mv != null || rs != null) return
        if (h === tapFig && tapCount > 0) tapCount++ else {
            tapCount = 1
            tapFig = h
        }
        tapAt = px
        tapWorld = w
        tapJob?.cancel()
        tapJob = scope.launch {
            delay(350)
            resolveTap()
        }
    }

    private fun resolveTap() {
        val h = tapFig
        val n = tapCount
        tapCount = 0
        if (n == 0 || h == null || !shapes.contains(h) || h.dieAt != 0L) return
        if (n == 1) {
            openEdit(h)
            if (h.t == "fx" && h.gd?.imp == false) trace = Trace(h, tapWorld.x)
            return
        }
        closeEdit(clearSel = false)
        sel = h
        when (n) {
            2 -> menu = MenuState(tapAt.x, tapAt.y, cmItems(), tapWorld)
            3 -> {
                mv = h
                showToast("Move mode ✥ — drag the figure, tap empty space when done")
            }
            4 -> if (h.t == "pt") showToast("Resize works on figures") else {
                rs = h
                showToast("Resize ⤢ — drag outward or inward, tap empty space when done")
            }
            else -> showToast("Bisectors arrive with the Parallel Lines tool (Phase 6)")
        }
    }

    // ---------- hold menu / double-tap menu ----------
    fun armHold(px: Offset) {
        holdFired = false
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(800)
            holdFired = true
            cursor = null
            draft = null
            holdAct(px)
        }
    }

    fun cancelHold() {
        holdJob?.cancel()
    }

    private fun holdAct(px: Offset) {
        val w = rawWorld(px)
        val h = hit(w)
        sel = h
        val items = buildList {
            if (h != null) {
                add(MenuItem("copy", "Copy", "📋"))
                add(MenuItem("cut", "Cut", "✂️"))
                add(MenuItem("del", "Delete", "🗑️"))
            }
            if (clip != null) add(MenuItem("paste", "Paste", "📥"))
        }
        if (items.isNotEmpty()) menu = MenuState(px.x, px.y, items, w)
        else showToast("Nothing to paste yet — double-tap a figure to copy it")
    }

    private fun cmItems(): List<MenuItem> = buildList {
        add(MenuItem("copy", "Copy", "📋"))
        add(MenuItem("cut", "Cut", "✂️"))
        add(MenuItem("del", "Delete", "🗑️"))
        add(MenuItem("det", "Details", "⋯"))
        if (clip != null) add(MenuItem("paste", "Paste", "📥"))
    }

    fun hideMenu() {
        menu = null
        sel = null
    }

    fun menuAction(key: String) {
        val m = menu
        val s = sel
        if (key == "det") {
            menu = null
            sel = null
            if (s != null && shapes.contains(s)) {
                detailsOn = true
                openEdit(s)
            }
            return
        }
        when (key) {
            "copy", "cut" -> if (s != null) {
                clip = s.copyOf(System.nanoTime())
                if (key == "cut") remove(s)
                showToast(if (key == "cut") "Cut ✂️ — hold on the board to paste" else "Copied 📋 — hold on the board to paste")
            }
            "del" -> if (s != null) {
                remove(s)
                showToast("Deleted 🗑️")
            }
            "paste" -> if (m != null) paste(m.at)
        }
        hideMenu()
    }

    fun copySel(f: Fig, cut: Boolean) {
        clip = f.copyOf(System.nanoTime())
        if (cut) remove(f)
        showToast(if (cut) "Cut ✂️ — hold on the board to paste" else "Copied 📋 — hold on the board to paste")
    }

    fun paste(at: Pt) {
        val c = clip ?: return
        val q = camera.snapStep
        val ce = centerOf(c.t, c.p)
        val dx = floor((at.x - ce.x) / q + 0.5) * q
        val dy = floor((at.y - ce.y) / q + 0.5) * q
        val born = System.nanoTime()
        val n = c.copyOf(born)
        n.p = c.p.map { Pt(it.x + dx, it.y + dy) }
        if (c.nm != null) n.nm = LB[lbN++ % LB.size]
        shapes.add(n)
        bump(born)
        showToast("Pasted ✅")
    }
}
