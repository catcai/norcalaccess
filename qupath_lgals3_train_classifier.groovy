// Run on the image where you manually labeled detections as Lgals3 / Ignore*
// Trains a QuPath random forest object classifier and saves it as JSON
// That file is then used by qupath_lgals3_stardist_batch.groovy on the full cohort

import qupath.opencv.ml.OpenCVClassifiers
import qupath.opencv.ml.objects.OpenCVMLClassifier
import qupath.opencv.ml.objects.features.FeatureExtractors
import qupath.lib.objects.PathObjectFilter
import qupath.lib.objects.classes.PathClass
import qupath.lib.classifiers.object.ObjectClassifiers
import qupath.lib.io.GsonTools
import org.bytedeco.opencv.opencv_ml.RTrees

def classifierPath = "/Volumes/Webb Lab/Kelsey_paper revisions/GFAP/Lgals3Results/lgals3_classifier.json"

// ── Get labeled detections ────────────────────────────────────────────────────
def lgals3Class = PathClass.fromString("Lgals3")
def ignoreClass = PathClass.fromString("Ignore*")

def trainingDets = getDetectionObjects().findAll {
    it.getPathClass() == lgals3Class || it.getPathClass() == ignoreClass
}

if (trainingDets.isEmpty()) {
    print "ERROR: No labeled detections found — label detections as Lgals3 or Ignore* first"
    return
}

def nLgals3 = trainingDets.count { it.getPathClass() == lgals3Class }
def nIgnore  = trainingDets.count { it.getPathClass() == ignoreClass }
print "Training on: ${nLgals3} Lgals3, ${nIgnore} Ignore* (${trainingDets.size()} total)"

// ── Build components ──────────────────────────────────────────────────────────
def measurementNames = trainingDets[0].getMeasurementList().getMeasurementNames() as List
print "Features: ${measurementNames}"

def statModel        = OpenCVClassifiers.createStatModel(RTrees.class)
def featureExtractor = FeatureExtractors.createMeasurementListFeatureExtractor(measurementNames)
def pathClasses      = [lgals3Class, ignoreClass]
def imageData        = getCurrentImageData()

// ── Train the stat model in-place via the internal static method ──────────────
// Parameters: extractor, statModel, pathClasses, imageData, objects, train=true, fireUpdate=false
int nClassified = OpenCVMLClassifier.classifyObjects(
    featureExtractor, statModel, pathClasses, imageData, trainingDets, true, false
)
print "Training complete — ${nClassified} objects classified during training"

// ── Wrap trained model into a saveable classifier ─────────────────────────────
def filter     = PathObjectFilter.DETECTIONS_ALL
def classifier = OpenCVMLClassifier.create(statModel, filter, featureExtractor, pathClasses)

// ── Save to JSON ──────────────────────────────────────────────────────────────
def outFile = new File(classifierPath)
outFile.getParentFile().mkdirs()

def gson = GsonTools.getInstance(true)
outFile.text = gson.toJson(classifier)

print "Classifier saved to: ${classifierPath}"
print "Now run qupath_lgals3_stardist_batch.groovy on your full cohort."
