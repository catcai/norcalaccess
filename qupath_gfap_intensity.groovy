import qupath.lib.gui.scripting.QPEx
import qupath.lib.objects.PathAnnotationObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// --- Configuration ---
// Set the channel name that corresponds to your GFAP stain
def GFAP_CHANNEL = "GFAP"

// Output CSV path — writes to the project directory
def project = getProject()
def outputPath = buildFilePath(PROJECT_BASE_DIR, "gfap_intensity.csv")

def timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now())
def file = new File(outputPath)
def writeHeader = !file.exists() || file.length() == 0

def writer = new FileWriter(file, true)  // append mode

if (writeHeader) {
    writer.write("Image,Annotation,Mean_GFAP_Intensity,Annotation_Area_mm2,Timestamp\n")
}

// Get the current image name
def imageName = getProjectEntry().getImageName()

// Skip if this image has already been processed
if (file.exists()) {
    def lines = file.readLines()
    def alreadyDone = lines.size() > 1 && lines.tail().any { it.startsWith(imageName + ",") }
    if (alreadyDone) {
        print "Skipping ${imageName} — already in CSV."
        writer.close()
        return
    }
}

// Iterate over all annotations in the current image
def annotations = getAnnotationObjects()

if (annotations.isEmpty()) {
    print "No annotations found in ${imageName} — skipping."
    writer.close()
    return
}

for (annotation in annotations) {
    def annotationName = annotation.getName() ?: annotation.getPathClass()?.toString() ?: "Unnamed"

    // Measure if not already measured
    def measurements = annotation.getMeasurementList()

    // Look for the GFAP mean intensity measurement (channel name may vary)
    def meanKey = measurements.getMeasurementNames().find { it.contains(GFAP_CHANNEL) && it.toLowerCase().contains("mean") }

    if (meanKey == null) {
        def server = getCurrentImageData().getServer()
        def roi = annotation.getROI()
        def request = qupath.lib.regions.RegionRequest.createInstance(server.getPath(), 1, roi)
        def img = qupath.lib.common.GeneralTools.toBufferedImage(server.readRegion(request))
        def channelIdx = server.getMetadata().getChannels().findIndexOf { it.getName() == GFAP_CHANNEL }
        if (channelIdx < 0) {
            print "WARNING: Channel '${GFAP_CHANNEL}' not found. Available channels: " +
                server.getMetadata().getChannels().collect { it.getName() }
        } else {
            def raster = img.getRaster()
            double sum = 0
            int count = 0
            def geom = roi.getGeometry()
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    sum += raster.getSampleDouble(x, y, channelIdx)
                    count++
                }
            }
            double mean = count > 0 ? sum / count : Double.NaN
            annotation.getMeasurementList().putMeasurement("${GFAP_CHANNEL}: Mean", mean)
            annotation.getMeasurementList().close()
            meanKey = "${GFAP_CHANNEL}: Mean"
        }
    }

    def meanIntensity = meanKey ? measurements.getMeasurementValue(meanKey) : Double.NaN

    // Area in mm²
    def areaMm2 = annotation.getROI().getArea() * Math.pow(getCurrentImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons() / 1000.0, 2)
    areaMm2 = Math.round(areaMm2 * 10000) / 10000.0

    writer.write("${imageName},${annotationName},${String.format('%.4f', meanIntensity)},${areaMm2},${timestamp}\n")
    print "  ${annotationName}: mean GFAP = ${String.format('%.4f', meanIntensity)}, area = ${areaMm2} mm²"
}

writer.close()
print "\nResults appended to: ${outputPath}"
