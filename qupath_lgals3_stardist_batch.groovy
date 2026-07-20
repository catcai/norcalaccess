// STEP 2: Run this on the full cohort after training and saving your object classifier.
// Set CLASSIFIER_PATH to the .json file saved from QuPath's object classifier trainer.

import qupath.ext.stardist.StarDist2D
import qupath.lib.classifiers.object.ObjectClassifiers

def modelPath       = "/Volumes/Webb Lab/Kelsey_paper revisions/GFAP/Scenes seperated/6 mo gfap/maxprojectedtiffs/scripts/dsb2018_heavy_augment.pb"
def classifierPath  = "/Volumes/Webb Lab/Kelsey_paper revisions/GFAP/Lgals3Results/lgals3_classifier.json"
def outputDir       = "/Volumes/Webb Lab/Kelsey_paper revisions/GFAP/Lgals3Results"
def resultsFile     = outputDir + "/lgals3_classified_results.csv"

def DETECTION_CHANNEL     = 'AF647_112'
def PERCENTILE_LOW        = 1.0
def PERCENTILE_HIGH       = 99.8
def PROBABILITY_THRESHOLD = 0.4
def PIXEL_SIZE_MICRONS    = 0.5
def MIN_AREA_UM2          = 20.0
def MAX_AREA_UM2          = 1200.0
def POSITIVE_CLASS        = "Lgals3"  // must match the class name you used when labeling

// ── Output setup ──────────────────────────────────────────────────────────────
def outDir = new File(outputDir)
if (!outDir.exists()) outDir.mkdirs()

def csv = new File(resultsFile)
if (!csv.exists()) {
    csv.text = "Image,Annotation,DG_Area_mm2,Total_Lgals3,Lgals3_Density_per_mm2,Timestamp\n"
}

// ── Load classifier ───────────────────────────────────────────────────────────
def classifier = ObjectClassifiers.readClassifier(new File(classifierPath))
if (classifier == null) {
    print "ERROR: Could not load classifier from ${classifierPath}"
    return
}

// ── Build StarDist detector ───────────────────────────────────────────────────
def stardist = StarDist2D.builder(modelPath)
    .channels(DETECTION_CHANNEL)
    .normalizePercentiles(PERCENTILE_LOW, PERCENTILE_HIGH)
    .threshold(PROBABILITY_THRESHOLD)
    .pixelSize(PIXEL_SIZE_MICRONS)
    .measureShape()
    .measureIntensity()
    .build()

// ── Process current image ─────────────────────────────────────────────────────
def imageData = getCurrentImageData()
def server    = imageData.getServer()
def imageName = server.getMetadata().getName()
def cal       = server.getPixelCalibration()

def annotations = getAnnotationObjects().findAll {
    it.getPathClass()?.toString() == "DG" || it.getName()?.toUpperCase() == "DG"
}

if (annotations.isEmpty()) {
    print "No DG annotations found in: ${imageName}"
    return
}

annotations.each { annotation ->
    def label = annotation.getName() ?: annotation.getPathClass()?.toString() ?: "DG"

    // Clear previous detections inside this annotation
    def toRemove = getDetectionObjects().findAll { det ->
        annotation.getROI().contains(
            det.getROI().getCentroidX(),
            det.getROI().getCentroidY()
        )
    }
    removeObjects(toRemove, false)

    // Run StarDist
    stardist.detectObjects(imageData, [annotation])

    // Loose area filter
    def allDets = getDetectionObjects().findAll { det ->
        annotation.getROI().contains(det.getROI().getCentroidX(), det.getROI().getCentroidY())
    }
    def sizeFiltered = allDets.findAll { det ->
        double areaUm2 = det.getROI().getArea() * cal.getPixelWidthMicrons() * cal.getPixelHeightMicrons()
        areaUm2 >= MIN_AREA_UM2 && areaUm2 <= MAX_AREA_UM2
    }
    removeObjects(allDets - sizeFiltered, false)
    fireHierarchyUpdate()

    // Apply object classifier
    classifier.classifyObjects(imageData, sizeFiltered, true)
    fireHierarchyUpdate()

    // Count only detections classified as positive
    def positiveClass = qupath.lib.objects.classes.PathClassFactory.getPathClass(POSITIVE_CLASS)
    def positiveDets = sizeFiltered.findAll { det ->
        det.getPathClass() == positiveClass
    }

    def count  = positiveDets.size()
    def areaPx = annotation.getROI().getArea()
    double areaMm2 = areaPx * cal.getPixelWidthMicrons() * cal.getPixelHeightMicrons() / 1e6
    double density = areaMm2 > 0 ? count / areaMm2 : 0

    def timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
    csv.append("${imageName},${label},${String.format('%.4f', areaMm2)},${count},${String.format('%.2f', density)},${timestamp}\n")

    print "  ${label}: ${count} Lgals3+ | ${String.format('%.1f', density)} /mm² | ${sizeFiltered.size()} total detections before classification"
}

fireHierarchyUpdate()
print "Done: ${imageName}"
