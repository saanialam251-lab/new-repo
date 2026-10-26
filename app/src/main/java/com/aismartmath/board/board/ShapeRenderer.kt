package com.aismartmath.board.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import com.aismartmath.board.math.angDeg
import com.aismartmath.board.math.center
import com.aismartmath.board.math.dist
import com.aismartmath.board.math.isect
import com.aismartmath.board.math.polyArea
import com.aismartmath.board.math.pts
import com.aismartmath.board.model.Fig
import com.aismartmath.board.model.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

val PointColor = Color(0xFFF87171)
val SegColor = Color(0xFFFBBF24)

/** Web: COL */
fun figColor(t: String): Color = when (t) {
    "pt" -> PointColor
    "seg" -> SegColor
    "tri" -> Color(0xFF818CF8)
    "rtri" -> Color(0xFFA78BFA)
    "quad" -> Color(0xFFF472B6)
    "rect" -> Color(0xFFFB7185)
    "circ" -> Color(0xFF2DD4BF)
    else -> Color(0xFF38BDF8)
}

/** Web: PXC - every angle has its own colour. */
val AngleColors = listOf(Color(0xFFF472B6), Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFF60A5FA))

/** Web: SW - colour swatches. */
val Swatches = listOf(
    Color(0xFF818CF8), Color(0xFFF472B6), Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFF60A5FA),
    Color(0xFFFB923C), Color(0xFFA78BFA), Color(0xFFF87171), Color(0xFF2DD4BF), Color(0xFFE5E7EB),
)

/** Web: pop-in curve (ease-out-back). */
fun easeOutBack(k: Float): Float =
    if (k >= 1f) 1f else 1f + 2.70158f * (k - 1f).pow(3) + 1.70158f * (k - 1f).pow(2)

private fun Float.toDeg(): Float = (this * 180f / PI.toFloat())

private fun aw(p: Offset, q: Offset, d: Float): Offset {
    val dx = p.x - q.x
    val dy = p.y - q.y
    val l = hypot(dx, dy).let { if (it == 0f) 1f else it }
    return Offset(p.x + dx / l * d, p.y + dy / l * d)
}

private fun DrawScope.scr(cam: Camera, p: Pt) = Offset(cam.sx(p.x), cam.sy(p.y))

private fun DrawScope.dot(cam: Camera, p: Pt, c: Color, a: Float, r: Float = 5f) {
    drawCircle(c.copy(alpha = a), r.dp.toPx(), scr(cam, p))
}

private fun DrawScope.seg(cam: Camera, p: Pt, q: Pt, c: Color, w: Float, a: Float, dashed: Boolean = false) {
    drawLine(
        c.copy(alpha = c.alpha * a),
        scr(cam, p),
        scr(cam, q),
        w.dp.toPx(),
        StrokeCap.Round,
        if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null,
    )
}

/** Web: arc() - right-angle square or a filled wedge at a vertex, in the vertex's own colour. */
private fun DrawScope.angleMark(cam: Camera, p: Pt, q: Pt, r: Pt, an: Double, c: Color, a: Float) {
    val X = cam.sx(p.x)
    val Y = cam.sy(p.y)
    val a1 = atan2(cam.sy(q.y) - Y, cam.sx(q.x) - X)
    val a2 = atan2(cam.sy(r.y) - Y, cam.sx(r.x) - X)
    val rad = 20.dp.toPx()
    if (abs(an - 90) < 0.6) {
        val ux = cos(a1) * rad * 0.7f
        val uy = sin(a1) * rad * 0.7f
        val wx = cos(a2) * rad * 0.7f
        val wy = sin(a2) * rad * 0.7f
        val path = Path().apply {
            moveTo(X + ux, Y + uy)
            lineTo(X + ux + wx, Y + uy + wy)
            lineTo(X + wx, Y + wy)
        }
        drawPath(path, c.copy(alpha = a), style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
    } else {
        val tau = 2 * PI.toFloat()
        val dl = (((a2 - a1) % tau) + tau) % tau
        val s1 = if (dl <= PI) a1 else a2
        val sweep = if (dl <= PI) dl else tau - dl
        val tl = Offset(X - rad, Y - rad)
        val sz = Size(rad * 2, rad * 2)
        drawArc(c.copy(alpha = 0.27f * a), s1.toDeg(), sweep.toDeg(), true, tl, sz, style = Fill)
        drawArc(c.copy(alpha = a), s1.toDeg(), sweep.toDeg(), false, tl, sz, style = Stroke(2.dp.toPx()))
    }
}

/** Web: wedge() - shaded angle where two lines cross. */
private fun DrawScope.wedge(X: Float, Y: Float, a1: Float, a2: Float, rad: Float, c: Color) {
    val tau = 2 * PI.toFloat()
    val dl = (((a2 - a1) % tau) + tau) % tau
    val s1 = if (dl <= PI) a1 else a2
    val sweep = if (dl <= PI) dl else tau - dl
    val tl = Offset(X - rad, Y - rad)
    val sz = Size(rad * 2, rad * 2)
    drawArc(c.copy(alpha = 0.4f), s1.toDeg(), sweep.toDeg(), true, tl, sz, style = Fill)
    drawArc(c, s1.toDeg(), sweep.toDeg(), true, tl, sz, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
}

private fun DrawScope.drawPoly(cam: Camera, m: TextMeasurer, P: List<Pt>, c: Color, nm: String?, quiet: Boolean, axes: Boolean, a: Float) {
    val n = P.size
    if (n < 2) {
        P.firstOrNull()?.let { dot(cam, it, c, a) }
        return
    }
    val path = Path().apply {
        P.forEachIndexed { i, p -> if (i == 0) moveTo(cam.sx(p.x), cam.sy(p.y)) else lineTo(cam.sx(p.x), cam.sy(p.y)) }
        if (n > 2) close()
    }
    if (n > 2) drawPath(path, c.copy(alpha = 0.2f * a), style = Fill)
    drawPath(path, c.copy(alpha = 0.13f * a), style = Stroke(8.dp.toPx(), join = StrokeJoin.Round))
    drawPath(path, c.copy(alpha = a), style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
    if (quiet) {
        P.forEach { dot(cam, it, c, a) }
        return
    }
    val g = Offset(P.sumOf { cam.sx(it.x).toDouble() }.toFloat() / n, P.sumOf { cam.sy(it.y).toDouble() }.toFloat() / n)
    val names = nm ?: "ABCD"
    for (i in 0 until n) {
        val p = P[i]
        val nx = P[(i + 1) % n]
        dot(cam, p, c, a)
        if (!(n == 2 && i == 1)) {
            val mid = Offset((cam.sx(p.x) + cam.sx(nx.x)) / 2, (cam.sy(p.y) + cam.sy(nx.y)) / 2)
            val lp = aw(mid, g, 13.dp.toPx())
            outlinedText(m, fmt(dist(p, nx)), lp.x, lp.y, Color.White, 12f, a)
        }
        if (n > 2) {
            val q = P[(i + n - 1) % n]
            val an = angDeg(p, q, nx)
            val ps = scr(cam, p)
            val label = names[i].toString() + if (axes) "(${fmt(p.x)}, ${fmt(p.y)})" else ""
            val vp = aw(ps, g, (if (axes) 30 else 16).dp.toPx())
            outlinedText(m, label, vp.x, vp.y, c, 13f, a)
            val col = AngleColors[i % 4]
            angleMark(cam, p, q, nx, an, col, a)
            val tp = aw(ps, g, (-40).dp.toPx())
            outlinedText(m, fmt(an, 0) + "°", tp.x, tp.y, col, 11f, a)
        }
    }
}

/** Triangle/quad extras: medians, centroid, midpoints, diagonals, diameter. */
private fun DrawScope.drawOverlays(cam: Camera, m: TextMeasurer, f: Fig, P: List<Pt>, c: Color, a: Float) {
    val white = Color.White.copy(alpha = 0.7f)
    val n = P.size
    if (f.t == "circ") {
        if ("dia" in f.ov) {
            val o = f.p[0]
            val e = f.p[1]
            val far = Pt(2 * o.x - e.x, 2 * o.y - e.y)
            seg(cam, far, e, white, 1.8f, a, dashed = true)
            val mp = Offset(cam.sx(o.x), cam.sy(o.y) + 14.dp.toPx())
            outlinedText(m, "d = " + fmt(2 * dist(o, e)), mp.x, mp.y, Color(0xFFE5E7EB), 11f, a)
        }
        return
    }
    if (n < 3) return
    val mids = List(n) { i -> Pt((P[i].x + P[(i + 1) % n].x) / 2, (P[i].y + P[(i + 1) % n].y) / 2) }
    if ("diag" in f.ov && n == 4) {
        seg(cam, P[0], P[2], white, 1.8f, a, dashed = true)
        seg(cam, P[1], P[3], white, 1.8f, a, dashed = true)
    }
    val cen = Pt(P.sumOf { it.x } / n, P.sumOf { it.y } / n)
    if ("med" in f.ov && n == 3) {
        for (i in 0 until 3) seg(cam, P[i], mids[(i + 1) % 3], white, 1.8f, a, dashed = true)
    }
    if ("cen" in f.ov) {
        dot(cam, cen, Color.White, a, 4f)
        val ps = scr(cam, cen)
        outlinedText(m, "G", ps.x + 10.dp.toPx(), ps.y - 10.dp.toPx(), Color.White, 12f, a)
    }
    if ("mid" in f.ov) mids.forEach { dot(cam, it, Color(0xFFE5E7EB), a, 3.5f) }
}

/** Web: drawS - draws one figure. [sc] is the pop-in/out scale, [a] the opacity. */
fun DrawScope.drawFig(cam: Camera, m: TextMeasurer, f: Fig, sc: Float, a: Float, quiet: Boolean, axes: Boolean) {
    if (a <= 0f) return
    val c = f.col ?: figColor(f.t)
    val an = center(f)
    val P = pts(f)
    withTransform({ scale(sc, sc, Offset(cam.sx(an.x), cam.sy(an.y))) }) {
        when (f.t) {
            "pt" -> {
                val p = P[0]
                drawCircle(c.copy(alpha = 0.18f * a), 10.dp.toPx(), scr(cam, p))
                dot(cam, p, c, a)
                outlinedText(m, "(${fmt(p.x)}, ${fmt(p.y)})", cam.sx(p.x), cam.sy(p.y) - 14.dp.toPx(), Color.White, 12f, a)
            }
            "seg" -> {
                val p = P[0]
                val q = P[1]
                seg(cam, p, q, c.copy(alpha = 0.16f), 8f, a)
                seg(cam, p, q, c, 2.5f, a)
                dot(cam, p, c, a)
                dot(cam, q, c, a)
                outlinedText(m, fmt(dist(p, q)), (cam.sx(p.x) + cam.sx(q.x)) / 2, (cam.sy(p.y) + cam.sy(q.y)) / 2 - 12.dp.toPx(), Color.White, 12f, a)
            }
            "circ" -> {
                val o = P[0]
                val e = P[1]
                val r = dist(o, e)
                val rpx = (r * cam.u).toFloat()
                drawCircle(c.copy(alpha = 0.2f * a), rpx, scr(cam, o), style = Fill)
                drawCircle(c.copy(alpha = 0.13f * a), rpx, scr(cam, o), style = Stroke(8.dp.toPx()))
                drawCircle(c.copy(alpha = a), rpx, scr(cam, o), style = Stroke(2.5.dp.toPx()))
                seg(cam, o, e, c, 2.5f, a)
                dot(cam, o, c, a)
                dot(cam, e, c, a)
                outlinedText(m, "r = " + fmt(r), (cam.sx(o.x) + cam.sx(e.x)) / 2, (cam.sy(o.y) + cam.sy(e.y)) / 2 - 12.dp.toPx(), Color.White, 12f, a)
            }
            else -> drawPoly(cam, m, P, c, f.nm, quiet, axes, a)
        }
        if (!quiet && f.ov.isNotEmpty()) drawOverlays(cam, m, f, P, c, a)
    }
}

/** Web: quiet mode - when polygons overlap only the original (or the selected one) shows labels. */
fun quietSet(shapes: List<Fig>, active: Fig?): Set<Fig> {
    val pl = shapes.filter { it.dieAt == 0L && it.t in setOf("tri", "quad", "rect", "rtri") }
    val boxes = pl.associateWith { s ->
        val q = pts(s)
        doubleArrayOf(q.minOf { it.x }, q.maxOf { it.x }, q.minOf { it.y }, q.maxOf { it.y })
    }
    fun ov(a: Fig, b: Fig): Boolean {
        val p = boxes.getValue(a)
        val q = boxes.getValue(b)
        return p[0] < q[1] && q[0] < p[1] && p[2] < q[3] && q[2] < p[3]
    }
    val ac = active?.takeIf { it in pl }
    val out = HashSet<Fig>()
    pl.forEachIndexed { i, s ->
        val o = pl.filter { it !== s && ov(s, it) }
        if (o.isEmpty()) return@forEachIndexed
        val a = if (ac != null && (ac === s || o.contains(ac))) ac else null
        val quiet = if (a != null) s !== a else o.any { pl.indexOf(it) < i }
        if (quiet) out.add(s)
    }
    return out
}

private fun edgesOf(f: Fig): List<Pair<Pt, Pt>> {
    if (f.t == "seg") return listOf(f.p[0] to f.p[1])
    if (f.t !in setOf("tri", "quad", "rect", "rtri")) return emptyList()
    val q = pts(f)
    return q.indices.map { q[it] to q[(it + 1) % q.size] }
}

/** Web: shaded angle wedges where figures cross (limited to 40 like the web version). */
fun DrawScope.drawCrossings(cam: Camera, shapes: List<Fig>, quiet: Set<Fig>) {
    val groups = shapes.filter { it.dieAt == 0L && it !in quiet }.map { edgesOf(it) }.filter { it.isNotEmpty() }
    var n = 0
    for (i in groups.indices) for (j in i + 1 until groups.size) {
        for ((a, b) in groups[i]) for ((c, d) in groups[j]) {
            if (n >= 40) return
            val h = isect(a, b, c, d) ?: continue
            val q = h.p
            if (listOf(a, b, c, d).any { dist(q, it) < 1e-6 }) continue
            n++
            val ux = b.x - a.x
            val uy = b.y - a.y
            var vx = d.x - c.x
            var vy = d.y - c.y
            if (ux * vx + uy * vy < 0) {
                vx = -vx
                vy = -vy
            }
            wedge(cam.sx(q.x), cam.sy(q.y), atan2(-uy, ux).toFloat(), atan2(-vy, vx).toFloat(), 22.dp.toPx(), Color(0xFFFBBF24))
        }
    }
}

/** Selected figure: marching dashed border that pulses between white and the figure's own colour, plus a soft glow. */
fun DrawScope.drawSelection(cam: Camera, f: Fig, phase: Float, pulse: Float, active: Boolean) {
    val c = f.col ?: figColor(f.t)
    val line = if (active) Color(0xFFFBBF24) else lerp(Color.White, c, pulse)
    val glow = (if (active) Color(0xFFFBBF24) else c).copy(alpha = 0.10f + 0.14f * pulse)
    val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()), phase.dp.toPx())
    val P = pts(f)
    when (f.t) {
        "circ" -> {
            val r = (dist(P[0], P[1]) * cam.u).toFloat() + 8.dp.toPx()
            drawCircle(glow, r, scr(cam, P[0]), style = Stroke(12.dp.toPx()))
            drawCircle(line, r, scr(cam, P[0]), style = Stroke(3.dp.toPx(), pathEffect = dash))
        }
        "pt" -> {
            drawCircle(glow, 14.dp.toPx(), scr(cam, P[0]), style = Stroke(10.dp.toPx()))
            drawCircle(line, 14.dp.toPx(), scr(cam, P[0]), style = Stroke(3.dp.toPx(), pathEffect = dash))
        }
        else -> {
            val path = Path().apply {
                P.forEachIndexed { i, p -> if (i == 0) moveTo(cam.sx(p.x), cam.sy(p.y)) else lineTo(cam.sx(p.x), cam.sy(p.y)) }
                if (P.size > 2) close()
            }
            drawPath(path, glow, style = Stroke(12.dp.toPx(), join = StrokeJoin.Round))
            drawPath(path, line, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round, pathEffect = dash))
        }
    }
}

/** Drag handles: a white dot with a breathing ring in the figure's colour. */
fun DrawScope.drawHandles(cam: Camera, f: Fig, pulse: Float) {
    val c = f.col ?: figColor(f.t)
    for (i in 0 until hcount(f)) {
        val o = scr(cam, f.p[i])
        drawCircle(c.copy(alpha = 0.55f - 0.3f * pulse), (9f + 4f * pulse).dp.toPx(), o, style = Stroke(2.dp.toPx()))
        drawCircle(Color.White, 6.dp.toPx(), o)
        drawCircle(c, 6.dp.toPx(), o, style = Stroke(2.dp.toPx()))
    }
}

/** Ghost while the finger is down: the points already tapped, lines between them and the cursor. */
fun DrawScope.drawPending(cam: Camera, m: TextMeasurer, pend: List<Pt>, cursor: Pt?, tool: String) {
    val c = figColor(tool)
    val all = if (cursor != null) pend + cursor else pend
    for (i in 0 until all.size - 1) seg(cam, all[i], all[i + 1], c, 2f, 0.7f, dashed = true)
    pend.forEach { dot(cam, it, c, 1f) }
    if (cursor != null) {
        dot(cam, cursor, c, 0.7f)
        outlinedText(m, "(${fmt(cursor.x)}, ${fmt(cursor.y)})", cam.sx(cursor.x), cam.sy(cursor.y) - 14.dp.toPx(), Color.White, 12f, 0.8f)
    }
}
