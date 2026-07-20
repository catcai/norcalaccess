// Diagnostic script — run this to discover available QuPath 0.7 ML classifier API
// Paste the output here so we can write the correct classifier script

import qupath.opencv.ml.OpenCVClassifiers
import qupath.opencv.ml.objects.OpenCVMLClassifier
import qupath.opencv.ml.objects.features.FeatureExtractors

println "=== OpenCVClassifiers static methods ==="
OpenCVClassifiers.class.methods
    .findAll { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.declaringClass == OpenCVClassifiers.class }
    .sort { it.name }
    .each { println "  ${it.name}(${it.parameterTypes*.simpleName.join(', ')})" }

println "\n=== OpenCVMLClassifier static methods ==="
OpenCVMLClassifier.class.methods
    .findAll { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.declaringClass == OpenCVMLClassifier.class }
    .sort { it.name }
    .each { println "  ${it.name}(${it.parameterTypes*.simpleName.join(', ')})" }

println "\n=== FeatureExtractors static methods ==="
FeatureExtractors.class.methods
    .findAll { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.declaringClass == FeatureExtractors.class }
    .sort { it.name }
    .each { println "  ${it.name}(${it.parameterTypes*.simpleName.join(', ')})" }

println "\nDone — paste this output to get the correct API calls"
