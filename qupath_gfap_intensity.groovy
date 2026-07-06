import qupath.lib.gui.scripting.QPEx
import qupath.lib.objects.PathAnnotationObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// --- Configuration ---
def GFAP_CHANNEL = "AF555_112"
def BACKGROUND_PERCENTILE = 0.05  // bottom 5% of pixels used as background estimate

def project = getProject()
def outputPath = buildFilePath(PROJECT_BASE_DIR, "gfap_intensity_corrected.csv")

def timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now())
def file = new File(outputPath)
def writeHeader = !file.exists() || file.length() == 0

def writer = new FileWriter(file, true)

if (writeHeader) {
    writer.write("Image,Annotation,Mean_GFAP_Intensity,Background_Intensity,Mean_GFAP_Corrected,Annotation_Area_mm2,Timestamp\n")
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
def channelIdx = server.getMetadata().getChannels().findIndexOf { it.getName() == GFAP_CHANNEL }
if (channelIdx < 0) {
    print "WARNING: Channel '${GFAP_CHANNEL}' not found. Available channels: " +
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

    // Collect all pixel values
    List<Double> pixels = []
    for (int y = 0; y < img.getHeight(); y++) {
        for (int x = 0; x < img.getWidth(); x++) {
            pixels << raster.getSampleDouble(x, y, channelIdx)
        }
    }

    if (pixels.isEmpty()) {
        print "  ${annotationName}: no pixels found, skipping"
        continue
    }

    Collections.sort(pixels)

    double meanIntensity = pixels.sum() / pixels.size()

    // Background = mean of bottom BACKGROUND_PERCENTILE of pixels
    int bgCount = Math.max(1, (int)(pixels.size() * BACKGROUND_PERCENTILE))
    double background = pixels.subList(0, bgCount).sum() / bgCount

    double corrected = meanIntensity - background

    def areaMm2 = roi.getArea() * Math.pow(server.getPixelCalibration().getAveragedPixelSizeMicrons() / 1000.0, 2)
    areaMm2 = Math.round(areaMm2 * 10000) / 10000.0

    writer.write("${imageName},${annotationName},${String.format('%.4f', meanIntensity)},${String.format('%.4f', background)},${String.format('%.4f', corrected)},${areaMm2},${timestamp}\n")
    print "  ${annotationName}: raw = ${String.format('%.4f', meanIntensity)}, background = ${String.format('%.4f', background)}, corrected = ${String.format('%.4f', corrected)}"
}

writer.close()
print "\nResults appended to: ${outputPath}"
