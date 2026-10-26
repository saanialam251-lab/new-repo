package com.aismartmath.board.math

import com.aismartmath.board.model.Fig
import com.aismartmath.board.model.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Ported from the web helpers D, ang, area, pts, center, ok, dseg, inPoly, segAng, solveTri, isect. */

fun dist(a: Pt, b: Pt): Double = hypot(a.x - b.x, a.y - b.y)

fun angDeg(p: Pt, a: Pt, b: Pt): Double {
    val ux = a.x - p.x
    val uy = a.y - p.y
    val vx = b.x - p.x
    val vy = b.y - p.y
    val d = hypot(ux, uy) * hypot(vx, vy)
    val c = ((ux * vx + uy * vy) / (if (d == 0.0) 1.0 else d)).coerceIn(-1.0, 1.0)
    return acos(c) * 180 / PI
}

fun polyArea(p: List<Pt>): Double {
    var s = 0.0
    for (i in p.indices) {
        val a = p[i]
        val b = p[(i + 1) % p.size]
        s += a.x * b.y - b.x * a.y
    }
    return abs(s) / 2
}

fun ptsOf(t: String, p: List<Pt>): List<Pt> = when (t) {
    "rect" -> {
        val a = p[0]
        val b = p[1]
        listOf(a, Pt(b.x, a.y), b, Pt(a.x, b.y))
    }
    "rtri" -> {
        val a = p[0]
        val b = p[1]
        listOf(a, Pt(b.x, a.y), b)
    }
    else -> p
}

fun pts(f: Fig): List<Pt> = ptsOf(f.t, f.p)

fun centerOf(t: String, p: List<Pt>): Pt {
    if (t == "fx") return Pt(0.0, 0.0)
    if (t == "circ" || t == "pt") return p[0]
    val q = ptsOf(t, p)
    return Pt(q.sumOf { it.x } / q.size, q.sumOf { it.y } / q.size)
}

fun center(f: Fig): Pt = centerOf(f.t, f.p)

/** Web: ok(t,p) - does this make a real figure? */
fun figOk(t: String, p: List<Pt>): Boolean = when (t) {
    "pt" -> true
    "seg", "circ" -> dist(p[0], p[1]) > 1e-9
    "rect", "rtri" -> p[0].x != p[1].x && p[0].y != p[1].y
    else -> polyArea(p) > 1e-9
}

fun dseg(p: Pt, a: Pt, b: Pt): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val l = dx * dx + dy * dy
    val t = if (l != 0.0) (((p.x - a.x) * dx + (p.y - a.y) * dy) / l).coerceIn(0.0, 1.0) else 0.0
    return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
}

fun inPoly(p: Pt, q: List<Pt>): Boolean {
    var c = false
    var j = q.size - 1
    for (i in q.indices) {
        if ((q[i].y > p.y) != (q[j].y > p.y) &&
            p.x < (q[j].x - q[i].x) * (p.y - q[i].y) / (q[j].y - q[i].y) + q[i].x
        ) c = !c
        j = i
    }
    return c
}

fun segAngle(a: Pt, b: Pt): Double = (((atan2(b.y - a.y, b.x - a.x) * 180 / PI) % 180) + 180) % 180

/** Web: solveTri - rebuild a triangle after one side length is edited; null if the sides cannot close. */
fun solveTri(p: List<Pt>, k: String, v: Double): List<Pt>? {
    var ab = dist(p[0], p[1])
    var bc = dist(p[1], p[2])
    var ca = dist(p[2], p[0])
    when (k) {
        "ab" -> ab = v
        "bc" -> bc = v
        "ca" -> ca = v
    }
    if (ab + bc <= ca || ab + ca <= bc || bc + ca <= ab) return null
    val a = p[0]
    val th = atan2(p[1].y - a.y, p[1].x - a.x)
    val ux = kotlin.math.cos(th)
    val uy = kotlin.math.sin(th)
    val nx = -uy
    val ny = ux
    val cr = (p[1].x - a.x) * (p[2].y - a.y) - (p[1].y - a.y) * (p[2].x - a.x)
    val g = if (cr < 0) -1.0 else 1.0
    val x = (ab * ab + ca * ca - bc * bc) / (2 * ab)
    val y = sqrt(max(0.0, ca * ca - x * x))
    return listOf(
        a,
        Pt(a.x + ab * ux, a.y + ab * uy),
        Pt(a.x + x * ux + g * y * nx, a.y + x * uy + g * y * ny),
    )
}

class IsectHit(val p: Pt, val ang: Double)

/** Web: isect - where two segments cross (null when parallel or not touching). */
fun isect(a: Pt, b: Pt, c: Pt, d: Pt): IsectHit? {
    val rx = b.x - a.x
    val ry = b.y - a.y
    val sx = d.x - c.x
    val sy = d.y - c.y
    val cross = rx * sy - ry * sx
    if (abs(cross) < 1e-12) return null
    val t = ((c.x - a.x) * sy - (c.y - a.y) * sx) / cross
    val u = ((c.x - a.x) * ry - (c.y - a.y) * rx) / cross
    if (t < -1e-9 || t > 1 + 1e-9 || u < -1e-9 || u > 1 + 1e-9) return null
    val ang = acos(minOf(1.0, abs(rx * sx + ry * sy) / (hypot(rx, ry) * hypot(sx, sy)))) * 180 / PI
    return IsectHit(Pt(a.x + t * rx, a.y + t * ry), ang)
}
