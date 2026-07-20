// Run this on the image where you manually labeled detections as Lgals3 / Ignore*
// It trains a random forest classifier from those labels and saves it as a .json file
// That file is then used by qupath_lgals3_stardist_batch.groovy on all other images

import qupath.lib.objects.classes.PathClassFactory
import qupath.opencv.ml.objects.OpenCVMLClassifier
import qupath.opencv.ml.objects.features.FeatureExtractors
import qupath.opencv.ml.OpenCVClassifiers
import qupath.lib.classifiers.object.ObjectClassifiers
import qupath.lib.io.GsonTools

def classifierPath = "/Volumes/Webb Lab/Kelsey_paper revisions/GFAP/Lgals3Results/lgals3_classifier.json"

// ── Get labeled detections ────────────────────────────────────────────────────
def lgals3Class  = PathClassFactory.getPathClass("Lgals3")
def ignoreClass  = PathClassFactory.getPathClass("Ignore*")

def labeledDets = getDetectionObjects().findAll {
    it.getPathClass() == lgals3Class || it.getPathClass() == ignoreClass
}

if (labeledDets.isEmpty()) {
    print "ERROR: No labeled detections found. Make sure you have assigned Lgals3 and Ignore* classes to detections."
    return
}

def nLgals3 = labeledDets.count { it.getPathClass() == lgals3Class }
def nIgnore = labeledDets.count { it.getPathClass() == ignoreClass }
print "Training on: ${nLgals3} Lgals3, ${nIgnore} Ignore* (${labeledDets.size()} total)"

// ── Build feature extractor from all measurements on labeled detections ───────
def imageData = getCurrentImageData()

def featureExtractor = FeatureExtractors.createMeasurementListFeatureExtractor(
    labeledDets.collect { it.getMeasurementList().getMeasurementNames() }.flatten().unique()
)

// ── Train random forest classifier ───────────────────────────────────────────
def classifier = OpenCVMLClassifier.createClassifier(
    OpenCVClassifiers.createRTreesClassifier(),
    featureExtractor,
    labeledDets,
    [lgals3Class, ignoreClass]
)

if (classifier == null) {
    print "ERROR: Classifier training failed."
    return
}

// ── Save classifier ───────────────────────────────────────────────────────────
def outFile = new File(classifierPath)
outFile.getParentFile().mkdirs()

def gson = GsonTools.getInstance(true)
outFile.text = gson.toJson(classifier)

print "Classifier saved to: ${classifierPath}"
print "Now run qupath_lgals3_stardist_batch.groovy on your full cohort."
