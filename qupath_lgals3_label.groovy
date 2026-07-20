// STEP 1: Run this script on 2-3 representative images to generate detections for manual labeling.
//
// After running:
//   1. In QuPath, use the brush/point tool to set classes on detections:
//        Right-click detection → Set class → "Lgals3" for real positive cells
//        Right-click detection → Set class → "Negative" for blobs/background artifacts
//   2. Label at least 20-30 examples of each class across your representative images
//   3. Train classifier: Classify → Object Classification → Train Object Classifier
//        - Features: select intensity, shape, and size measurements for AF647_112
//        - Save classifier to a .json file when done
//   4. Then run qupath_lgals3_stardist_batch.groovy on the full cohort

import qupath.ext.stardist.StarDist2D

def modelPath = "/Volumes/Webb Lab/Kelsey_paper revisions/GFAP/Scenes seperated/6 mo gfap/maxprojectedtiffs/scripts/dsb2018_heavy_augment.pb"

def DETECTION_CHANNEL     = 'AF647_112'
def PERCENTILE_LOW        = 1.0
def PERCENTILE_HIGH       = 99.8
def PROBABILITY_THRESHOLD = 0.4
def PIXEL_SIZE_MICRONS    = 0.5
def MIN_AREA_UM2          = 20.0    // only exclude obvious tiny debris
def MAX_AREA_UM2          = 1200.0  // keep large blobs so you can label them as Negative

def stardist = StarDist2D.builder(modelPath)
    .channels(DETECTION_CHANNEL)
    .normalizePercentiles(PERCENTILE_LOW, PERCENTILE_HIGH)
    .threshold(PROBABILITY_THRESHOLD)
    .pixelSize(PIXEL_SIZE_MICRONS)
    .measureShape()
    .measureIntensity()
    .build()

def imageData = getCurrentImageData()
def server    = imageData.getServer()
def cal       = server.getPixelCalibration()

def annotations = getAnnotationObjects().findAll {
    it.getPathClass()?.toString() == "DG" || it.getName()?.toUpperCase() == "DG"
}

if (annotations.isEmpty()) {
    print "No DG annotations found — check annotation class/name"
    return
}

// Clear previous detections
clearDetections()

stardist.detectObjects(imageData, annotations)

// Apply loose area filter only — keep blobs for manual labeling
def toRemove = getDetectionObjects().findAll { det ->
    double areaUm2 = det.getROI().getArea() * cal.getPixelWidthMicrons() * cal.getPixelHeightMicrons()
    areaUm2 < MIN_AREA_UM2 || areaUm2 > MAX_AREA_UM2
}
removeObjects(toRemove, false)

fireHierarchyUpdate()
print "Done — ${getDetectionObjects().size()} detections generated. Now manually label Lgals3 (positive) vs Negative (blobs) in the QuPath viewer."
