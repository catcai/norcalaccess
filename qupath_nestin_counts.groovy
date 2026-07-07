import qupath.lib.gui.scripting.QPEx
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// --- Configuration ---
def DAPI_CHANNEL   = "DAPI_112"
def NESTIN_CHANNEL = "AF488_112"
def SD_MULTIPLIER  = 1.0   // cells above (mean + SD_MULTIPLIER * SD) are nestin+

def outputPath = buildFilePath(PROJECT_BASE_DIR, "nestin_counts.csv")
def timestamp  = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now())
def file       = new File(outputPath)
def writeHeader = !file.exists() || file.length() == 0
def writer = new FileWriter(file, true)

if (writeHeader) {
    writer.write("Image,Annotation,Total_Cells,Nestin_Positive,Nestin_Negative,Nestin_Threshold,Annotation_Area_mm2,Timestamp\n")
}

def imageName = getProjectEntry().getImageName()

if (file.exists()) {
    def lines = file.readLines()
    def alreadyDone = lines.size() > 1 && lines.tail().any { it.startsWith(imageName + ",") }
    if (alreadyDone) {
        print "Skipping ${imageName} — already in CSV."
        writer.close()
        return
    }
}

def server = getCurrentImageData().getServer()
def channels = server.getMetadata().getChannels().collect { it.getName() }

def dapiIdx   = channels.findIndexOf { it == DAPI_CHANNEL }
def nestinIdx = channels.findIndexOf { it == NESTIN_CHANNEL }

if (dapiIdx < 0 || nestinIdx < 0) {
    print "WARNING: Channel not found. Available: ${channels}"
    writer.close()
    return
}

def annotations = getAnnotationObjects()
if (annotations.isEmpty()) {
    print "No annotations found in ${imageName} — skipping."
    writer.close()
    return
}

setImageType('FLUORESCENCE')

for (annotation in annotations) {
    def annotationName = annotation.getName() ?: annotation.getPathClass()?.toString() ?: "Unnamed"

    selectObjects([annotation])

    runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', [
        'detectionImageFluorescence' : dapiIdx,
        'requestedPixelSizeMicrons'  : 0.5,
        'backgroundRadiusMicrons'    : 8.0,
        'medianRadiusMicrons'        : 0.0,
        'sigmaMicrons'               : 1.5,
        'minAreaMicrons'             : 10.0,
        'maxAreaMicrons'             : 400.0,
        'threshold'                  : 200.0,
        'watershedPostProcess'       : true,
        'cellExpansionMicrons'       : 2.0,
        'includeNuclei'              : true,
        'smoothBoundaries'           : true,
        'makeMeasurements'           : true
    ])

    def detections = annotation.getChildObjects().findAll { it.isDetection() }

    if (detections.isEmpty()) {
        print "  ${annotationName}: no cells detected, skipping"
        continue
    }

    // Sample nestin intensity for each detected cell
    List<Double> nestinValues = []
    for (det in detections) {
        def roi = det.getROI()
        def request = qupath.lib.regions.RegionRequest.createInstance(server.getPath(), 1, roi)
        def img = server.readRegion(request)
        def raster = img.getRaster()

        double sum = 0
        int count = 0
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                sum += raster.getSampleDouble(x, y, nestinIdx)
                count++
            }
        }
        nestinValues << (count > 0 ? sum / count : 0.0)
    }

    // Compute mean and SD across all cells in this annotation
    double mean = nestinValues.sum() / nestinValues.size()
    double variance = nestinValues.collect { (it - mean) ** 2 }.sum() / nestinValues.size()
    double sd = Math.sqrt(variance)
    double threshold = mean + SD_MULTIPLIER * sd

    print "  ${annotationName}: nestin mean=${String.format('%.1f', mean)}, SD=${String.format('%.1f', sd)}, threshold=${String.format('%.1f', threshold)}"

    // Classify and mark detections
    int positive = 0, negative = 0
    [detections, nestinValues].transpose().each { det, val ->
        if (val >= threshold) {
            det.setPathClass(getPathClass("Nestin+"))
            positive++
        } else {
            det.setPathClass(getPathClass("Nestin-"))
            negative++
        }
    }

    fireHierarchyUpdate()

    def areaMm2 = annotation.getROI().getArea() * Math.pow(server.getPixelCalibration().getAveragedPixelSizeMicrons() / 1000.0, 2)
    areaMm2 = Math.round(areaMm2 * 10000) / 10000.0
    int total = positive + negative

    writer.write("${imageName},${annotationName},${total},${positive},${negative},${String.format('%.2f', threshold)},${areaMm2},${timestamp}\n")
    print "  ${annotationName}: ${positive}/${total} nestin+ (threshold=${String.format('%.1f', threshold)})"
}

writer.close()
print "\nResults appended to: ${outputPath}"
