package com.aismartmath.board.board

import com.aismartmath.board.math.MathNode
import com.aismartmath.board.math.plain
import com.aismartmath.board.math.angDeg
import com.aismartmath.board.math.dist
import com.aismartmath.board.math.polyArea
import com.aismartmath.board.math.pts
import com.aismartmath.board.math.segAngle
import com.aismartmath.board.model.Fig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** One piece of a "Details" line: plain text or a stacked fraction (web used KaTeX for these). */
sealed interface Part {
    class T(val s: String) : Part
    class F(val n: String, val d: String) : Part
    class M(val node: MathNode) : Part
}

/** Stable text of a list of parts (used as an animation key). */
fun partsKey(parts: List<Part>): String = parts.joinToString("") {
    when (it) {
        is Part.T -> it.s
        is Part.F -> "[" + it.n + "/" + it.d + "]"
        is Part.M -> it.node.plain()
    }
}

class Field(val key: String, val label: String, val value: Double, val ro: Boolean = false)

val FigNames = mapOf(
    "pt" to "Point", "seg" to "Line", "circ" to "Circle", "rect" to "Rectangle",
    "rtri" to "Right triangle", "tri" to "Triangle", "quad" to "Quadrilateral", "fx" to "Graph",
)

/** Web: fields() - the number boxes in the edit panel. */
fun fieldsOf(f: Fig): List<Field> {
    if (f.t == "fx") return emptyList()
    val p = f.p
    fun l(i: Int, j: Int) = dist(p[i], p[j])
    val q = p.getOrElse(1) { p[0] }
    val w = abs(q.x - p[0].x)
    val h = abs(q.y - p[0].y)
    return when (f.t) {
        "pt" -> listOf(Field("x", "x", p[0].x), Field("y", "y", p[0].y))
        "seg" -> listOf(Field("len", "Length", l(0, 1)), Field("ang", "Angle °", segAngle(p[0], p[1])))
        "circ" -> listOf(Field("r", "Radius", l(0, 1)))
        "rect" -> listOf(Field("w", "Width", w), Field("h", "Height", h))
        "rtri" -> listOf(Field("w", "Base", w), Field("h", "Height", h))
        "tri" -> listOf(Field("ab", "AB", l(0, 1)), Field("bc", "BC", l(1, 2)), Field("ca", "CA", l(2, 0)))
        "quad" -> listOf(
            Field("ab", "AB", l(0, 1), true), Field("bc", "BC", l(1, 2), true),
            Field("cd", "CD", l(2, 3), true), Field("da", "DA", l(3, 0), true),
        )
        else -> emptyList()
    }
}

/** Web: props()/desc() - the full description shown by Details, with fractions. */
fun propsOf(f: Fig): List<List<Part>> {
    if (f.t == "fx") return listOf(graphParts(f))
    fun t(s: String): Part = Part.T(s)
    fun fr(n: String, d: String): Part = Part.F(n, d)
    val P = pts(f)
    val n = P.size
    val a = f.p[0]
    val b = f.p.getOrElse(1) { a }
    when (f.t) {
        "pt" -> return listOf(listOf(t("Point (${fmt(a.x)}, ${fmt(a.y)})")))
        "seg" -> {
            val dx = b.x - a.x
            val dy = b.y - a.y
            return listOf(
                listOf(t("Length ${fmt(dist(a, b))}")),
                listOf(t("Angle ${fmt(segAngle(a, b), 1)}°")),
                listOf(t("Midpoint (${fmt((a.x + b.x) / 2)}, ${fmt((a.y + b.y) / 2)})")),
                if (dx == 0.0) listOf(t("Slope undefined"))
                else listOf(t("Slope m = "), fr(fmt(dy), fmt(dx)), t(" = ${fmt(dy / dx)}")),
            )
        }
        "circ" -> {
            val r = dist(a, b)
            return listOf(
                listOf(t("Radius ${fmt(r)}")),
                listOf(t("Diameter d = 2r = ${fmt(2 * r)}")),
                listOf(t("Circumference C = 2πr = ${fmt(2 * PI * r)}")),
                listOf(t("Area A = πr² = ${fmt(PI * r * r)}")),
                listOf(t("Quarter arc = "), fr("πr", "2"), t(" = ${fmt(PI * r / 2)}")),
            )
        }
        "rect" -> {
            val w = abs(b.x - a.x)
            val h = abs(b.y - a.y)
            return listOf(
                listOf(t("${if (w == h) "Square" else "Rectangle"} ${fmt(w)} × ${fmt(h)}")),
                listOf(t("Area A = w × h = ${fmt(w * h)}")),
                listOf(t("Perimeter P = ${fmt(2 * (w + h))}")),
                listOf(t("Diagonal ${fmt(hypot(w, h))}")),
            )
        }
        "rtri" -> {
            val nm = f.nm ?: "ABCD"
            val j = dist(P[0], P[1])
            val o = dist(P[1], P[2])
            val h = dist(P[0], P[2])
            val k = nm[0].toString()
            return listOf(
                listOf(t("Right angle at ${nm[1]}")),
                listOf(t("Legs ${fmt(j)} and ${fmt(o)}, hypotenuse ${fmt(h)}")),
                listOf(t("∠$k = ${fmt(atan2(o, j) * 180 / PI, 1)}°")),
                listOf(t("sin $k = "), fr(fmt(o), fmt(h)), t(" = ${fmt(o / h)}")),
                listOf(t("cos $k = "), fr(fmt(j), fmt(h)), t(" = ${fmt(j / h)}")),
                listOf(t("tan $k = "), fr(fmt(o), fmt(j)), t(" = ${fmt(o / j)}")),
                listOf(t("Area A = "), fr("base × height", "2"), t(" = ${fmt(j * o / 2)}")),
            )
        }
    }
    if (n == 3) {
        val sd = List(3) { dist(P[it], P[(it + 1) % 3]) }
        val ar = polyArea(P)
        val pr = sd.sum()
        val gx = P.sumOf { it.x } / 3
        val gy = P.sumOf { it.y } / 3
        return listOf(
            listOf(t("Area A = ${fmt(ar)}")),
            listOf(t("Perimeter P = ${fmt(pr)}")),
            listOf(t("Inradius r = "), fr("A", "s"), t(" = ${fmt(ar / (pr / 2))}")),
            listOf(t("Circumradius R = "), fr("abc", "4A"), t(" = ${fmt(sd[0] * sd[1] * sd[2] / (4 * ar))}")),
            listOf(t("Centroid (${fmt(gx)}, ${fmt(gy)})")),
            listOf(t("Angle sum 180°")),
        )
    }
    return listOf(
        listOf(t("Area A = ${fmt(polyArea(P))}")),
        listOf(t("Perimeter P = ${fmt(P.indices.sumOf { dist(P[it], P[(it + 1) % n]) })}")),
        listOf(t("Diagonals d₁ = ${fmt(dist(P[0], P[2]))}, d₂ = ${fmt(dist(P[1], P[3]))}")),
        listOf(t("Angle sum 360°")),
    )
}
