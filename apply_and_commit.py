#!/usr/bin/env python3
"""
apply_and_commit.py
-------------------
Applique les corrections SMB safety sur BetaCellPlugin.kt
et crée le commit git.

Usage (depuis la racine du repo AndroidAPS) :
    python3 apply_and_commit.py
"""
import subprocess, sys, os, textwrap

FPATH = "plugins/aps/src/main/kotlin/app/aaps/plugins/aps/betacell/BetaCellPlugin.kt"

COMMIT_MSG = textwrap.dedent("""\
    fix(betacell): suspend SMB 3 min after recent bolus — linear & sigmoid² modes

    Problème : ads.lastBolusTime n'existe pas sur AutosensDataStore.
    Solution  : utiliser IobTotal.lastBolusTime, champ natif AAPS mis à jour
                dans calculateIobFromBolusToTime() pour chaque bolus amount>0.
                Réutilise iobFromBolus déjà calculé pour iobTotal (pas de
                double appel à calculateIobFromBolus).

    Changements :
    - iobFromBolus = calculateIobFromBolus()  → lastBolusTime lu dessus
    - lastBolusAgeMs = now - iobFromBolus.lastBolusTime
    - recentBolus (< 3 min) passé en param à calcLinear() et calcNonLinear()
    - smbAllowed && !recentBolus dans les deux modes (6 conditions total)
    - [SMB_HOLD bolus Xs ago] dans buildReasonLinear et buildReasonNonLinear
""")

# ── Patches (old, new) ───────────────────────────────────────────────────────

PATCHES = [

    # 1. iobFromBolus — évite double appel, donne accès à lastBolusTime
    (
        "        // -- IOB total = bolus + basal actif ----------------------------------\n"
        "        val iobTotal = iobCobCalculator.calculateIobFromBolus().iob +\n"
        "            iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().iob\n",

        "        // -- IOB total = bolus + basal actif ----------------------------------\n"
        "        // iobFromBolus contient lastBolusTime — pas de double appel\n"
        "        val iobFromBolus = iobCobCalculator.calculateIobFromBolus()\n"
        "        val iobTotal = iobFromBolus.iob +\n"
        "            iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().iob\n"
    ),

    # 2. lastBolusTime : ads.lastBolusTime → iobFromBolus.lastBolusTime
    (
        "        // ── Latence bolus : calculée UNE SEULE FOIS, commune aux deux modes ──\n"
        "        val lastBolusAgeMs = System.currentTimeMillis() -\n"
        "            (iobCobCalculator.ads.lastBolusTime ?: 0L)\n",

        "        // ── Latence bolus : lastBolusTime lu depuis IobTotal (champ natif AAPS) ──\n"
        "        // Mis à jour dans calculateIobFromBolusToTime() pour chaque bolus amount>0\n"
        "        val lastBolusAgeMs = System.currentTimeMillis() - iobFromBolus.lastBolusTime\n"
    ),

    # 3. Switch : passer recentBolus + lastBolusAgeMs aux deux branches
    (
        "        return if (p.useNonLinear)\n"
        "            calcNonLinear(bg, slope, dtMin, isf, iobTotal, bgIn30min, hypoAlert, basalFactor, braked, p)\n"
        "        else\n"
        "            calcLinear(bg, slope, dtMin, isf, iobTotal, bgIn30min, hypoAlert, basalFactor, braked, p)\n",

        "        return if (p.useNonLinear)\n"
        "            calcNonLinear(bg, slope, dtMin, isf, iobTotal, bgIn30min, hypoAlert,\n"
        "                          basalFactor, braked, recentBolus, lastBolusAgeMs, p)\n"
        "        else\n"
        "            calcLinear(bg, slope, dtMin, isf, iobTotal, bgIn30min, hypoAlert,\n"
        "                       basalFactor, braked, recentBolus, lastBolusAgeMs, p)\n"
    ),

    # 4. calcLinear — signature + smbAllowed + buildReason call
    (
        "    private fun calcLinear(\n"
        "        bg: Double, slope: Double, dtMin: Double, isf: Double,\n"
        "        iobTotal: Double, bgIn30min: Double, hypoAlert: Double,\n"
        "        basalFactor: Double, braked: Boolean, p: BetaCellPrefs\n"
        "    ): BetaCellApsResult {\n",

        "    private fun calcLinear(\n"
        "        bg: Double, slope: Double, dtMin: Double, isf: Double,\n"
        "        iobTotal: Double, bgIn30min: Double, hypoAlert: Double,\n"
        "        basalFactor: Double, braked: Boolean,\n"
        "        recentBolus: Boolean, lastBolusAgeMs: Long,\n"
        "        p: BetaCellPrefs\n"
        "    ): BetaCellApsResult {\n"
    ),

    # 5. calcLinear — smbAllowed +!recentBolus
    (
        "        val smbAllowed = p.smbEnabled\n"
        "            && bg > p.targetBg + p.smbOffset\n"
        "            && bg < p.hyperBg  \n"
        "            && bgIn30min > hypoAlert\n"
        "            && iobTotal < p.smbMax * 3.0\n"
        "        val smb = if (smbAllowed) min(0.3 * systemicInsulin, p.smbMax) else 0.0\n"
        "\n"
        "        return BetaCellApsResult().also { r ->\n"
        "            r.rate = rate; r.smb = smb\n"
        "            r.isTempBasalRequested = rate > 0.0\n"
        "            r.duration = 30\n"
        "            r.betaSecretion   = beta\n"
        "            r.systemicInsulin = systemicInsulin\n"
        "            r.isf_used        = isf\n"
        "            r.slope_used      = slope\n"
        "            r.zone            = zoneOf(bg, p)\n"
        "            r.reason          = buildReasonLinear(\n"
        "                bg, slope, isf, beta, systemicInsulin,\n"
        "                p, braked, basalFactor, bgIn30min, iobTotal)\n"
        "        }\n"
        "    }\n",

        "        // ── SMB : 6 conditions ────────────────────────────────────────\n"
        "        val smbAllowed = p.smbEnabled\n"
        "            && !recentBolus                            // latence DB bolus\n"
        "            && bg > p.targetBg + p.smbOffset\n"
        "            && bg < p.hyperBg\n"
        "            && bgIn30min > hypoAlert\n"
        "            && iobTotal < p.smbMax * 3.0\n"
        "        val smb = if (smbAllowed) min(0.3 * systemicInsulin, p.smbMax) else 0.0\n"
        "\n"
        "        return BetaCellApsResult().also { r ->\n"
        "            r.rate = rate; r.smb = smb\n"
        "            r.isTempBasalRequested = rate > 0.0\n"
        "            r.duration = 30\n"
        "            r.betaSecretion   = beta\n"
        "            r.systemicInsulin = systemicInsulin\n"
        "            r.isf_used        = isf\n"
        "            r.slope_used      = slope\n"
        "            r.zone            = zoneOf(bg, p)\n"
        "            r.reason          = buildReasonLinear(\n"
        "                bg, slope, isf, beta, systemicInsulin,\n"
        "                p, braked, basalFactor, bgIn30min, iobTotal,\n"
        "                recentBolus, lastBolusAgeMs)\n"
        "        }\n"
        "    }\n"
    ),

    # 6. calcNonLinear — signature
    (
        "    private fun calcNonLinear(\n"
        "        bg: Double, slope: Double, dtMin: Double, isf: Double,\n"
        "        iobTotal: Double, bgIn30min: Double, hypoAlert: Double,\n"
        "        basalFactor: Double, braked: Boolean, p: BetaCellPrefs\n"
        "    ): BetaCellApsResult {\n",

        "    private fun calcNonLinear(\n"
        "        bg: Double, slope: Double, dtMin: Double, isf: Double,\n"
        "        iobTotal: Double, bgIn30min: Double, hypoAlert: Double,\n"
        "        basalFactor: Double, braked: Boolean,\n"
        "        recentBolus: Boolean, lastBolusAgeMs: Long,\n"
        "        p: BetaCellPrefs\n"
        "    ): BetaCellApsResult {\n"
    ),

    # 7. calcNonLinear — smbAllowed + buildReason call
    (
        "        // 9. SMB — bloque en hyper severe\n"
        "        val smbAllowed = p.smbEnabled\n"
        "            && bg > p.targetBg + p.smbOffset\n"
        "            && bg < p.hyperBg\n"
        "            && bgIn30min > hypoAlert\n"
        "            && iobTotal < p.smbMax * 3.0\n"
        "        val smb = if (smbAllowed) min(0.3 * systemicInsulin, p.smbMax) else 0.0\n"
        "\n"
        "        return BetaCellApsResult().also { r ->\n"
        "            r.rate = rate; r.smb = smb\n"
        "            r.isTempBasalRequested = rate > 0.0\n"
        "            r.duration = 30\n"
        "            r.betaSecretion   = beta\n"
        "            r.systemicInsulin = systemicInsulin\n"
        "            r.isf_used        = isf\n"
        "            r.slope_used      = slope\n"
        "            r.zone            = zoneOf(bg, p)\n"
        "            r.reason          = buildReasonNonLinear(\n"
        "                bg, slope, beta, systemicInsulin,\n"
        "                activation, caState, caDecay, hepaticEffective,\n"
        "                p, braked, basalFactor, bgIn30min, iobTotal, resistanceFactor)\n"
        "        }\n"
        "    }\n",

        "        // 9. SMB — 6 conditions\n"
        "        val smbAllowed = p.smbEnabled\n"
        "            && !recentBolus                            // latence DB bolus\n"
        "            && bg > p.targetBg + p.smbOffset\n"
        "            && bg < p.hyperBg\n"
        "            && bgIn30min > hypoAlert\n"
        "            && iobTotal < p.smbMax * 3.0\n"
        "        val smb = if (smbAllowed) min(0.3 * systemicInsulin, p.smbMax) else 0.0\n"
        "\n"
        "        return BetaCellApsResult().also { r ->\n"
        "            r.rate = rate; r.smb = smb\n"
        "            r.isTempBasalRequested = rate > 0.0\n"
        "            r.duration = 30\n"
        "            r.betaSecretion   = beta\n"
        "            r.systemicInsulin = systemicInsulin\n"
        "            r.isf_used        = isf\n"
        "            r.slope_used      = slope\n"
        "            r.zone            = zoneOf(bg, p)\n"
        "            r.reason          = buildReasonNonLinear(\n"
        "                bg, slope, beta, systemicInsulin,\n"
        "                activation, caState, caDecay, hepaticEffective,\n"
        "                p, braked, basalFactor, bgIn30min, iobTotal,\n"
        "                resistanceFactor, recentBolus, lastBolusAgeMs)\n"
        "        }\n"
        "    }\n"
    ),

    # 8. buildReasonLinear — signature + [SMB_HOLD]
    (
        "        basalFactor: Double, bgIn30min: Double, iobTotal: Double\n"
        "    ): String = buildString {\n"
        "        append(\"LINEAR BG=${bg.roundToInt()} tgt=${p.targetBg.roundToInt()} \")\n",

        "        basalFactor: Double, bgIn30min: Double, iobTotal: Double,\n"
        "        recentBolus: Boolean = false, lastBolusAgeMs: Long = Long.MAX_VALUE\n"
        "    ): String = buildString {\n"
        "        append(\"LINEAR BG=${bg.roundToInt()} tgt=${p.targetBg.roundToInt()} \")\n"
    ),

    # 9. buildReasonLinear — insérer [SMB_HOLD] avant [OPEN_LOOP]
    (
        "        append(\"b=${\"%.3f\".format(beta)}U sys=${\"%.3f\".format(systemic)}U \")\n"
        "        if (p.openLoopOnly)    append(\"[OPEN_LOOP]\")\n"
        "    }\n"
        "\n"
        "    private fun buildReasonNonLinear(",

        "        append(\"b=${\"%.3f\".format(beta)}U sys=${\"%.3f\".format(systemic)}U \")\n"
        "        if (recentBolus) append(\"[SMB_HOLD bolus ${lastBolusAgeMs / 1000}s ago] \")\n"
        "        if (p.openLoopOnly)    append(\"[OPEN_LOOP]\")\n"
        "    }\n"
        "\n"
        "    private fun buildReasonNonLinear("
    ),

    # 10. buildReasonNonLinear — signature + [SMB_HOLD]
    (
        "        iobTotal: Double, resistanceFactor: Double\n"
        "    ): String = buildString {\n"
        "        append(\"SIGMOID2 BG=${bg.roundToInt()} tgt=${p.targetBg.roundToInt()} \")\n",

        "        iobTotal: Double, resistanceFactor: Double,\n"
        "        recentBolus: Boolean = false, lastBolusAgeMs: Long = Long.MAX_VALUE\n"
        "    ): String = buildString {\n"
        "        append(\"SIGMOID2 BG=${bg.roundToInt()} tgt=${p.targetBg.roundToInt()} \")\n"
    ),

    # 11. buildReasonNonLinear — insérer [SMB_HOLD] avant [OPEN_LOOP]
    (
        "        append(\"b=${\"%.3f\".format(beta)}U sys=${\"%.3f\".format(systemic)}U \")\n"
        "        if (p.openLoopOnly)         append(\"[OPEN_LOOP]\")\n"
        "    }\n"
        "\n"
        "    override fun addPreferenceScreen(",

        "        append(\"b=${\"%.3f\".format(beta)}U sys=${\"%.3f\".format(systemic)}U \")\n"
        "        if (recentBolus) append(\"[SMB_HOLD bolus ${lastBolusAgeMs / 1000}s ago] \")\n"
        "        if (p.openLoopOnly)         append(\"[OPEN_LOOP]\")\n"
        "    }\n"
        "\n"
        "    override fun addPreferenceScreen("
    ),
]

# ── Main ─────────────────────────────────────────────────────────────────────

def run(cmd, **kw):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        print(f"ERROR: {cmd}\n{r.stderr}", file=sys.stderr)
        sys.exit(1)
    return r.stdout.strip()

# Vérifier qu'on est dans la racine du repo
if not os.path.exists(".git"):
    print("ERROR: lancez ce script depuis la racine du repo AndroidAPS", file=sys.stderr)
    sys.exit(1)

if not os.path.exists(FPATH):
    print(f"ERROR: fichier introuvable : {FPATH}", file=sys.stderr)
    sys.exit(1)

# Lire le fichier
with open(FPATH, encoding="utf-8") as f:
    content = f.read()

original = content

# Appliquer les patches
for i, (old, new) in enumerate(PATCHES, 1):
    if old not in content:
        print(f"ERROR: patch {i} — séquence non trouvée. Déjà appliqué ?", file=sys.stderr)
        print(f"  Attendu: {repr(old[:80])}", file=sys.stderr)
        sys.exit(1)
    content = content.replace(old, new)
    print(f"  patch {i:2d} ✓")

# Vérifications post-patch
checks = [
    ("iobFromBolus.lastBolusTime",     "lastBolusTime depuis IobTotal"),
    ("!recentBolus",                   "recentBolus dans smbAllowed (×2)"),
    ("[SMB_HOLD bolus",                "[SMB_HOLD] dans reason"),
    ("recentBolus: Boolean, lastBolusAgeMs: Long,\n        p: BetaCellPrefs", "params calcLinear"),
]
ok = True
for token, desc in checks:
    count = content.count(token)
    status = "✓" if count > 0 else "✗ MANQUANT"
    print(f"  check {status}: {desc} (×{count})")
    if count == 0:
        ok = False

if not ok:
    print("ERROR: vérifications échouées — fichier non modifié", file=sys.stderr)
    sys.exit(1)

# Écrire le fichier
with open(FPATH, "w", encoding="utf-8") as f:
    f.write(content)
print(f"\n✓ {FPATH} mis à jour ({content.count(chr(10))+1} lignes)")

# Git add + commit
run(f'git add "{FPATH}"')
run(f'git commit -m "{COMMIT_MSG}"')
sha = run("git rev-parse --short HEAD")
print(f"✓ commit {sha} créé sur {run('git branch --show-current')}")
