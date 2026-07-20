import qupath.ext.stardist.StarDist2D
import qupath.lib.images.servers.ColorTransforms
import qupath.lib.objects.PathAnnotationObject

// ── Configuration ─────────────────────────────────────────────────────────────
def modelPath   = "/Volumes/Webb Lab/Kelsey_paper revisions/GFAP/Scenes seperated/6 mo gfap/maxprojectedtiffs/scripts/dsb2018_heavy_augment.pb"
def outputDir   = "/Volumes/Webb Lab/Kelsey_paper revisions/GFAP/Lgals3Results"
def resultsFile = outputDir + "/lgals3_stardist_results.csv"

// StarDist detection parameters — tune these first on one representative image
def DETECTION_CHANNEL    = 'AF647_112'  // Lgals3 channel
def PERCENTILE_LOW       = 1.0          // normalisation floor
def PERCENTILE_HIGH      = 99.8         // normalisation ceiling — lower if bright cells blow out
def PROBABILITY_THRESHOLD = 0.35        // lower = more detections, higher = fewer/more confident
def NMS_OVERLAP           = 0.25        // non-max suppression overlap threshold
def PIXEL_SIZE_MICRONS    = 0.5         // your image pixel size in µm
def MIN_AREA_UM2          = 20.0        // exclude very small debris
def MAX_AREA_UM2          = 800.0       // exclude large merged blobs

// ── Output setup ──────────────────────────────────────────────────────────────
def outDir = new File(outputDir)
if (!outDir.exists()) outDir.mkdirs()

def csv = new File(resultsFile)
if (!csv.exists()) {
    csv.text = "Image,Annotation,DG_Area_mm2,Total_Lgals3,Lgals3_Density_per_mm2,Timestamp\n"
}

// ── Build StarDist detector ───────────────────────────────────────────────────
def stardist = StarDist2D.builder(modelPath)
    .channels(DETECTION_CHANNEL)
    .normalizePercentiles(PERCENTILE_LOW, PERCENTILE_HIGH)
    .threshold(PROBABILITY_THRESHOLD)
    .overlap(NMS_OVERLAP)
    .pixelSize(PIXEL_SIZE_MICRONS)
    .measureShape()
    .measureIntensity()
    .build()

// ── Process each image ────────────────────────────────────────────────────────
def imageData = getCurrentImageData()
def server    = imageData.getServer()
def imageName = server.getMetadata().getName()
def cal       = server.getPixelCalibration()

def annotations = getAnnotationObjects().findAll {
    it.getPathClass() == null || it.getPathClass()?.toString() == "DG" ||
    it.getName()?.toUpperCase() == "DG"
}

if (annotations.isEmpty()) {
    print "No DG annotations found in: ${imageName}"
    return
}

annotations.each { annotation ->
    def label = annotation.getName() ?: annotation.getPathClass()?.toString() ?: "DG"

    // Remove any previous detections inside this annotation
    def toRemove = getDetectionObjects().findAll { detection ->
        annotation.getROI().contains(
            detection.getROI().getCentroidX(),
            detection.getROI().getCentroidY()
        )
    }
    removeObjects(toRemove, false)

    // Run StarDist within this annotation
    stardist.detectObjects(imageData, [annotation])

    // Collect detections inside annotation, filter by area
    def detections = getDetectionObjects().findAll { det ->
        annotation.getROI().contains(
            det.getROI().getCentroidX(),
            det.getROI().getCentroidY()
        )
    }.findAll { det ->
        def areaPx = det.getROI().getArea()
        double areaUm2 = areaPx * cal.getPixelWidthMicrons() * cal.getPixelHeightMicrons()
        areaUm2 >= MIN_AREA_UM2 && areaUm2 <= MAX_AREA_UM2
    }

    // Remove detections that failed the area filter
    def allInAnnotation = getDetectionObjects().findAll { det ->
        annotation.getROI().contains(
            det.getROI().getCentroidX(),
            det.getROI().getCentroidY()
        )
    }
    def toRemoveFiltered = allInAnnotation - detections
    removeObjects(toRemoveFiltered, false)

    def count  = detections.size()
    def areaPx = annotation.getROI().getArea()
    double areaMm2 = areaPx * cal.getPixelWidthMicrons() * cal.getPixelHeightMicrons() / 1e6
    double density = areaMm2 > 0 ? count / areaMm2 : 0

    def timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
    csv.append("${imageName},${label},${String.format('%.4f', areaMm2)},${count},${String.format('%.2f', density)},${timestamp}\n")

    print "  ${label}: ${count} Lgals3+ cells | ${String.format('%.1f', density)} /mm² | area ${String.format('%.3f', areaMm2)} mm²"
}

fireHierarchyUpdate()
print "Done: ${imageName}"
