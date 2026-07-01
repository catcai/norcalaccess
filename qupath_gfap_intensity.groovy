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
        // Run intensity measurements if not present
        selectObjects(annotation)
        runPlugin('qupath.lib.algorithms.IntensityFeaturesPlugin',
            '{"pixelSizeMicrons": 1.0, "region": "ROI", "tileSizeMicrons": 25, ' +
            '"colorOD": false, "colorStain1": false, "colorStain2": false, "colorStain3": false, ' +
            '"colorRed": false, "colorGreen": false, "colorBlue": false, ' +
            '"colorHue": false, "colorSaturation": false, "colorBrightness": false, ' +
            '"doMean": true, "doStdDev": false, "doMinMax": false, "doMedian": false, "doHaralick": false}')
        meanKey = measurements.getMeasurementNames().find { it.contains(GFAP_CHANNEL) && it.toLowerCase().contains("mean") }
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
