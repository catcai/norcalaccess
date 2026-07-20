// Run on the image where you manually labeled detections as Lgals3 / Ignore*
// Derives classification thresholds from your labeled examples and saves them to JSON
// That JSON is used by qupath_lgals3_stardist_batch.groovy on the full cohort

import groovy.json.JsonOutput
import qupath.lib.objects.classes.PathClass

def classifierPath = "/Volumes/Webb Lab/Kelsey_paper revisions/GFAP/Lgals3Results/lgals3_classifier.json"

// ── Get labeled detections ────────────────────────────────────────────────────
def lgals3Class = PathClass.fromString("Lgals3")
def ignoreClass = PathClass.fromString("Ignore*")

def lgals3Dets = getDetectionObjects().findAll { it.getPathClass() == lgals3Class }
def ignoreDets  = getDetectionObjects().findAll { it.getPathClass() == ignoreClass }

if (lgals3Dets.isEmpty() || ignoreDets.isEmpty()) {
    print "ERROR: Need labeled detections of both classes. Found ${lgals3Dets.size()} Lgals3, ${ignoreDets.size()} Ignore*"
    return
}

print "Training on: ${lgals3Dets.size()} Lgals3, ${ignoreDets.size()} Ignore*"

// ── Helper: get measurement safely ───────────────────────────────────────────
def getMeasure = { det, key ->
    def val = det.getMeasurementList().getMeasurementValue(key)
    return (val == null || val.isNaN()) ? null : val
}

def meanOf = { list -> list.sum() / list.size() }
def sdOf   = { list ->
    def m = meanOf(list)
    Math.sqrt(list.collect { (it - m) ** 2 }.sum() / (list.size() - 1))
}

// ── Find available measurement keys ──────────────────────────────────────────
def sampleDet  = lgals3Dets[0]
def allKeys    = sampleDet.getMeasurementList().getMeasurementNames() as List
print "Available measurements: ${allKeys}"

// Pick the intensity key — try common QuPath naming variants
def intensityKey = allKeys.find { it.contains("AF647") && it.contains("Mean") } ?:
                   allKeys.find { it.contains("647") && it.contains("Mean") }

def areaKey = allKeys.find { it.contains("Area") }

if (!intensityKey) {
    print "ERROR: Could not find AF647 Mean intensity measurement. Available: ${allKeys}"
    return
}

print "Using intensity key: ${intensityKey}"
print "Using area key: ${areaKey}"

// ── Compute class statistics ──────────────────────────────────────────────────
def lgals3Int  = lgals3Dets.collect { getMeasure(it, intensityKey) }.findAll { it != null }
def ignoreInt  = ignoreDets.collect  { getMeasure(it, intensityKey) }.findAll { it != null }

def lgals3IntMean = meanOf(lgals3Int)
def lgals3IntSD   = sdOf(lgals3Int)
def ignoreIntMean = meanOf(ignoreInt)
def ignoreIntSD   = sdOf(ignoreInt)

// Optimal intensity threshold: midpoint weighted by SDs
double intThreshold = (lgals3IntMean / lgals3IntSD + ignoreIntMean / ignoreIntSD) /
                      (1.0 / lgals3IntSD + 1.0 / ignoreIntSD)

print "Lgals3 mean intensity: ${String.format('%.0f', lgals3IntMean)} ± ${String.format('%.0f', lgals3IntSD)}"
print "Ignore mean intensity:  ${String.format('%.0f', ignoreIntMean)} ± ${String.format('%.0f', ignoreIntSD)}"
print "Optimal intensity threshold: ${String.format('%.0f', intThreshold)}"

// Area stats (optional second feature)
def lgals3Area = areaKey ? lgals3Dets.collect { getMeasure(it, areaKey) }.findAll { it != null } : []
def ignoreArea  = areaKey ? ignoreDets.collect  { getMeasure(it, areaKey) }.findAll { it != null } : []

def params = [
    intensity_key:        intensityKey,
    area_key:             areaKey,
    intensity_threshold:  intThreshold,
    lgals3_int_mean:      lgals3IntMean,
    lgals3_int_sd:        lgals3IntSD,
    ignore_int_mean:      ignoreIntMean,
    ignore_int_sd:        ignoreIntSD,
    lgals3_area_mean:     lgals3Area ? meanOf(lgals3Area) : null,
    lgals3_area_sd:       lgals3Area ? sdOf(lgals3Area)   : null,
    ignore_area_mean:     ignoreArea  ? meanOf(ignoreArea)  : null,
    ignore_area_sd:       ignoreArea  ? sdOf(ignoreArea)    : null,
    n_lgals3_training:    lgals3Dets.size(),
    n_ignore_training:    ignoreDets.size(),
    trained_on:           getCurrentImageData().getServer().getMetadata().getName()
]

// ── Save to JSON ──────────────────────────────────────────────────────────────
def outFile = new File(classifierPath)
outFile.getParentFile().mkdirs()
outFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(params))

print "Classifier saved to: ${classifierPath}"
print "Now run qupath_lgals3_stardist_batch.groovy on your full cohort."
