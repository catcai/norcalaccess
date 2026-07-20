// Run on the image where you manually labeled detections as Lgals3 / Ignore*
// Trains a QuPath random forest object classifier and saves it as JSON
// That file is then used by qupath_lgals3_stardist_batch.groovy on the full cohort

import qupath.opencv.ml.OpenCVClassifiers
import qupath.opencv.ml.objects.OpenCVMLClassifier
import qupath.opencv.ml.objects.features.FeatureExtractors
import qupath.lib.objects.PathObjectFilter
import qupath.lib.objects.classes.PathClass
import qupath.lib.io.GsonTools

import org.bytedeco.opencv.opencv_core.*
import org.bytedeco.opencv.opencv_ml.*
import static org.bytedeco.opencv.global.opencv_core.*
import static org.bytedeco.opencv.global.opencv_ml.*

def classifierPath = "/Volumes/Webb Lab/Kelsey_paper revisions/GFAP/Lgals3Results/lgals3_classifier.json"

// ── Get labeled detections ────────────────────────────────────────────────────
def lgals3Class = PathClass.fromString("Lgals3")
def ignoreClass = PathClass.fromString("Ignore*")

def lgals3Dets = getDetectionObjects().findAll { it.getPathClass() == lgals3Class }
def ignoreDets  = getDetectionObjects().findAll { it.getPathClass() == ignoreClass }
def trainingDets = lgals3Dets + ignoreDets

if (trainingDets.isEmpty()) {
    print "ERROR: No labeled detections found — label detections as Lgals3 or Ignore* first"
    return
}
print "Training on: ${lgals3Dets.size()} Lgals3, ${ignoreDets.size()} Ignore* (${trainingDets.size()} total)"

// ── Get measurement names ─────────────────────────────────────────────────────
def measurementNames = trainingDets[0].getMeasurementList().getMeasurementNames() as List
int nFeatures = measurementNames.size()
int nSamples  = trainingDets.size()
print "Using ${nFeatures} features"

// ── Inspect MeasurementList API ───────────────────────────────────────────────
def sampleML = trainingDets[0].getMeasurementList()
print "MeasurementList class: ${sampleML.getClass().getName()}"
print "MeasurementList methods: ${sampleML.getClass().methods.findAll { it.declaringClass == sampleML.getClass() || it.declaringClass.name.contains('qupath') }.collect { it.name }.unique().sort()}"

// ── Build OpenCV training matrices ────────────────────────────────────────────
def samples   = new Mat(nSamples, nFeatures, CV_32F)
def responses = new Mat(nSamples, 1, CV_32S)

trainingDets.eachWithIndex { det, i ->
    def ml = det.getMeasurementList()
    measurementNames.eachWithIndex { name, j ->
        double val = ml.getMeasurementValue(j)
        float fval = (Double.isNaN(val)) ? 0f : (float)val
        samples.ptr(i, j).putFloat(fval)
    }
    int classIdx = (det.getPathClass() == lgals3Class) ? 1 : 0
    responses.ptr(i, 0).putInt(classIdx)
}

// ── Train RTrees ──────────────────────────────────────────────────────────────
def rTrees    = RTrees.create()
def trainData = TrainData.create(samples, ROW_SAMPLE, responses)
boolean trained = rTrees.train(trainData)

if (!trained) {
    print "ERROR: RTrees training failed"
    return
}
print "RTrees training successful"

// ── Wrap in QuPath classifier and save ───────────────────────────────────────
def statModel        = OpenCVClassifiers.wrapStatModel(rTrees)
def featureExtractor = FeatureExtractors.createMeasurementListFeatureExtractor(measurementNames)
def filter           = PathObjectFilter.DETECTIONS_ALL
def pathClasses      = [lgals3Class, ignoreClass]

def classifier = OpenCVMLClassifier.create(statModel, filter, featureExtractor, pathClasses)

def outFile = new File(classifierPath)
outFile.getParentFile().mkdirs()

def gson = GsonTools.getInstance(true)
outFile.text = gson.toJson(classifier)

print "Classifier saved to: ${classifierPath}"
print "Now run qupath_lgals3_stardist_batch.groovy on your full cohort."
