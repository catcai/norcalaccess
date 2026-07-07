import qupath.lib.gui.scripting.QPEx
import qupath.lib.objects.PathAnnotationObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// --- Configuration ---
def GFAP_CHANNEL = "AF555_112"
def DAPI_CHANNEL = "DAPI_112"

def project = getProject()
def outputPath = buildFilePath(PROJECT_BASE_DIR, "gfap_dapi_normalized.csv")

def timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now())
def file = new File(outputPath)
def writeHeader = !file.exists() || file.length() == 0

def writer = new FileWriter(file, true)

if (writeHeader) {
    writer.write("Image,Annotation,Mean_GFAP_Intensity,Mean_DAPI_Intensity,GFAP_DAPI_Ratio,Annotation_Area_mm2,Timestamp\n")
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

def annotations = getAnnotationObjects()

if (annotations.isEmpty()) {
    print "No annotations found in ${imageName} — skipping."
    writer.close()
    return
}

def server = getCurrentImageData().getServer()

def gfapIdx = server.getMetadata().getChannels().findIndexOf { it.getName() == GFAP_CHANNEL }
def dapiIdx = server.getMetadata().getChannels().findIndexOf { it.getName() == DAPI_CHANNEL }

if (gfapIdx < 0 || dapiIdx < 0) {
    print "WARNING: Channel not found. Available channels: " +
        server.getMetadata().getChannels().collect { it.getName() }
    writer.close()
    return
}

for (annotation in annotations) {
    def annotationName = annotation.getName() ?: annotation.getPathClass()?.toString() ?: "Unnamed"

    def roi = annotation.getROI()
    def request = qupath.lib.regions.RegionRequest.createInstance(server.getPath(), 1, roi)
    def img = server.readRegion(request)
    def raster = img.getRaster()

    double gfapSum = 0, dapiSum = 0
    int count = 0
    for (int y = 0; y < img.getHeight(); y++) {
        for (int x = 0; x < img.getWidth(); x++) {
            gfapSum += raster.getSampleDouble(x, y, gfapIdx)
            dapiSum += raster.getSampleDouble(x, y, dapiIdx)
            count++
        }
    }

    if (count == 0) {
        print "  ${annotationName}: no pixels found, skipping"
        continue
    }

    double meanGFAP = gfapSum / count
    double meanDAPI = dapiSum / count
    double ratio = meanDAPI > 0 ? meanGFAP / meanDAPI : Double.NaN

    def areaMm2 = roi.getArea() * Math.pow(server.getPixelCalibration().getAveragedPixelSizeMicrons() / 1000.0, 2)
    areaMm2 = Math.round(areaMm2 * 10000) / 10000.0

    writer.write("${imageName},${annotationName},${String.format('%.4f', meanGFAP)},${String.format('%.4f', meanDAPI)},${String.format('%.6f', ratio)},${areaMm2},${timestamp}\n")
    print "  ${annotationName}: GFAP = ${String.format('%.4f', meanGFAP)}, DAPI = ${String.format('%.4f', meanDAPI)}, ratio = ${String.format('%.6f', ratio)}"
}

writer.close()
print "\nResults appended to: ${outputPath}"
