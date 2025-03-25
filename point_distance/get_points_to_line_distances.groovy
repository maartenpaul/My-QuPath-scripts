// Script for measuring distances from detection points to polygon boundaries

Script written by Maarten Paul, LUMC, contact: m.w.paul@lumc.nl
v1.0.0 25 March 2025

// This script:
// 1. Convert annotations to detections (with option to remove originals)
// 2. Measure distances from detections to both "endo" and "epi" polygons
// 3. Display distance measurements visually with lines
// 4. Increase detection point size for better visibility

// Get the image data and hierarchy
def imageData = getCurrentImageData()
def hierarchy = imageData.getHierarchy()

// Convert annotations to detections if needed
def convertAnnotationsToDetections = true  // Set to false to skip this step
if (convertAnnotationsToDetections) {
    // Find annotations with class "fibre"
    def fibreAnnotations = getAnnotationObjects().findAll{it.getPathClass() == getPathClass("fibre")}
    if (!fibreAnnotations.isEmpty()) {
        // Convert each annotation to a detection object
        def newDetections = []
        
        for (annotation in fibreAnnotations) {
            def roi = annotation.getROI()
            
            // Handle different ROI types
            if (roi.isPoint()) {
                // For point ROIs, create one detection per point
                def points = roi.getAllPoints()
                for (point in points) {
                    def pointROI = ROIs.createPointsROI(point.getX(), point.getY(), roi.getImagePlane())
                    def detection = PathObjects.createDetectionObject(pointROI, annotation.getPathClass())
                    newDetections.add(detection)
                }
            } else {
                // For other ROI types, create a single detection
                def detection = PathObjects.createDetectionObject(roi, annotation.getPathClass())
                newDetections.add(detection)
            }
        }
        
        // Remove original annotations and add new detections
        removeObjects(fibreAnnotations, true)
        addObjects(newDetections)
        print("Converted " + fibreAnnotations.size() + " annotations to " + newDetections.size() + " detections")
    }
}

// Make detection points larger for better visibility
// Use PathPrefs to adjust point display settings
import qupath.lib.gui.prefs.PathPrefs
PathPrefs.pointRadiusProperty().set(8)  // Increase point size

// Refresh display
try {
    def viewer = getCurrentViewer()
    viewer.repaint()
} catch (Exception e) {
    // Silently continue if this fails
}

// Get all detections for processing
def detections = getDetectionObjects()
if (detections.isEmpty()) {
    print("No detections found! Please create detection objects first.")
    return
}

// Get all endothelial and epithelial annotations
def endoAnnotations = getAnnotationObjects().findAll{it.getPathClass() == getPathClass("endo")}
def epiAnnotations = getAnnotationObjects().findAll{it.getPathClass() == getPathClass("epi")}

// Check if we have the required annotations
if (endoAnnotations.isEmpty() && epiAnnotations.isEmpty()) {
    print("Warning: No 'endo' or 'epi' annotations found! Please create at least one polygon annotation.")
    return
}

if (endoAnnotations.isEmpty()) {
    print("Warning: No 'endo' annotations found. Only measuring distance to 'epi'.")
}

if (epiAnnotations.isEmpty()) {
    print("Warning: No 'epi' annotations found. Only measuring distance to 'endo'.")
}

if (endoAnnotations.size() > 1) {
    print("Warning: Multiple 'endo' annotations found (" + endoAnnotations.size() + "). Will measure to the closest one.")
}

if (epiAnnotations.size() > 1) {
    print("Warning: Multiple 'epi' annotations found (" + epiAnnotations.size() + "). Will measure to the closest one.")
}

// Function to find closest point on polygon
def findClosestPointOnPolygon(x, y, annotation) {
    def roi = annotation.getROI()
    def vertices = roi.getAllPoints()
    
    double minDistance = Double.MAX_VALUE
    double closestX = 0
    double closestY = 0
    
    // Loop through all line segments of the polygon
    for (int i = 0; i < vertices.size()-1; i++) {
        def v1 = vertices[i]
        def v2 = vertices[(i + 1) ] 
        
        // Line segment coordinates
        def x1 = v1.getX()
        def y1 = v1.getY()
        def x2 = v2.getX()
        def y2 = v2.getY()
        
        // Check for degenerate line segment
        if (Math.abs(x2-x1) < 1e-10 && Math.abs(y2-y1) < 1e-10) {
            continue  // Skip zero-length segments
        }
        
        // Vector math to find projection point
        def A = x - x1
        def B = y - y1
        def C = x2 - x1
        def D = y2 - y1
        
        // Project point onto line segment
        def dot = (A * C) + (B * D)
        def lenSq = (C * C) + (D * D)
        
        // Avoid division by zero for degenerate line segments
        double param = 0
        if (lenSq > 1e-10) {
            param = dot / lenSq
        }
        
        // Calculate closest point coordinates
        double xx, yy
        
        if (param < 0) {
            xx = x1
            yy = y1
        } else if (param > 1) {
            xx = x2
            yy = y2
        } else {
            xx = x1 + param * C
            yy = y1 + param * D
        }
        
        // Calculate distance to this point
        def dx = x - xx
        def dy = y - yy
        def distance = Math.sqrt(dx * dx + dy * dy)
        
        // Update if this is the closest point found so far
        if (distance < minDistance) {
            minDistance = distance
            closestX = xx
            closestY = yy
        }
    }
    
    return [closestX, closestY, minDistance]
}

// Process each detection
for (det in detections) {
    // Get detection coordinates
    def roi = det.getROI()
    double x, y
    
    if (roi instanceof qupath.lib.roi.PointsROI) {
        // For point annotations
        def point = roi.getAllPoints()[0]
        x = point.getX()
        y = point.getY()
    } else {
        // For other types (cells, etc.)
        x = roi.getCentroidX()
        y = roi.getCentroidY()
    }
    
    // Get pixel size for conversion to microns
    def pixelSize = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons()
    
    // Measure distance to endo annotations if available
    if (!endoAnnotations.isEmpty()) {
        // Find closest point across all endo annotations
        double endoMinDistance = Double.MAX_VALUE
        double endoClosestX = 0
        double endoClosestY = 0
        
        for (annotation in endoAnnotations) {
            def result = findClosestPointOnPolygon(x, y, annotation)
            if (result[2] < endoMinDistance) {
                endoMinDistance = result[2]
                endoClosestX = result[0]
                endoClosestY = result[1]
            }
        }
        
        // Create a line showing the minimum distance
        def plane = det.getROI().getImagePlane()
        def lineROI = ROIs.createLineROI(x, y, endoClosestX, endoClosestY, plane)
        def lineAnnotation = PathObjects.createAnnotationObject(lineROI)
        lineAnnotation.setPathClass(getPathClass("Endo Distance"))
        
        // Calculate distance in microns
        def endoDistanceMicrons = endoMinDistance * pixelSize
        
        // Add distance to line annotation name
        lineAnnotation.setName(String.format("Endo: %.1f µm", endoDistanceMicrons))
        
        // Store the closest point coordinates in the detection measurements
        try {
            det.measurements.put("Closest endo point X", endoClosestX)
            det.measurements.put("Closest endo point Y", endoClosestY)
        } catch (Exception e) {
            try {
                det.getMeasurementList().addMeasurement("Closest endo point X", endoClosestX)
                det.getMeasurementList().addMeasurement("Closest endo point Y", endoClosestY)
            } catch (Exception e2) {
                // Silent fail
            }
        }
        
        // Create a point to mark the closest point
        def pointROI = ROIs.createPointsROI(endoClosestX, endoClosestY, plane)
        def pointAnnotation = PathObjects.createAnnotationObject(pointROI) 
        pointAnnotation.setPathClass(getPathClass("Endo Point"))
        
        // Set the name of the point annotation with the distance
        pointAnnotation.setName(String.format("Endo: %.1f µm", endoDistanceMicrons))
        
        // Add to hierarchy
        hierarchy.addObject(lineAnnotation)
        hierarchy.addObject(pointAnnotation)
        
        // Update measurements
        try {
            det.measurements.put("Distance to endo (µm)", endoDistanceMicrons)
        } catch (Exception e) {
            try {
                det.getMeasurementList().addMeasurement("Distance to endo (µm)", endoDistanceMicrons)
            } catch (Exception e2) {
                print("Error updating endo measurement")
            }
        }
    }
    
    // Measure distance to epi annotations if available
    if (!epiAnnotations.isEmpty()) {
        // Find closest point across all epi annotations
        double epiMinDistance = Double.MAX_VALUE
        double epiClosestX = 0
        double epiClosestY = 0
        
        for (annotation in epiAnnotations) {
            def result = findClosestPointOnPolygon(x, y, annotation)
            if (result[2] < epiMinDistance) {
                epiMinDistance = result[2]
                epiClosestX = result[0]
                epiClosestY = result[1]
            }
        }
        
        // Create a line showing the minimum distance
        def plane = det.getROI().getImagePlane()
        def lineROI = ROIs.createLineROI(x, y, epiClosestX, epiClosestY, plane)
        def lineAnnotation = PathObjects.createAnnotationObject(lineROI)
        lineAnnotation.setPathClass(getPathClass("Epi Distance"))
        
        // Calculate distance in microns
        def epiDistanceMicrons = epiMinDistance * pixelSize
        
        // Add distance to line annotation name
        lineAnnotation.setName(String.format("Epi: %.1f µm", epiDistanceMicrons))
        
        // Store the closest point coordinates in the detection measurements
        try {
            det.measurements.put("Closest epi point X", epiClosestX)
            det.measurements.put("Closest epi point Y", epiClosestY)
        } catch (Exception e) {
            try {
                det.getMeasurementList().addMeasurement("Closest epi point X", epiClosestX)
                det.getMeasurementList().addMeasurement("Closest epi point Y", epiClosestY)
            } catch (Exception e2) {
                // Silent fail
            }
        }
        
        // Create a point to mark the closest point
        def pointROI = ROIs.createPointsROI(epiClosestX, epiClosestY, plane)
        def pointAnnotation = PathObjects.createAnnotationObject(pointROI) 
        pointAnnotation.setPathClass(getPathClass("Epi Point"))
        
        // Set the name of the point annotation with the distance
        pointAnnotation.setName(String.format("Epi: %.1f µm", epiDistanceMicrons))
        
        // Add to hierarchy
        hierarchy.addObject(lineAnnotation)
        hierarchy.addObject(pointAnnotation)
        
        // Update measurements
        try {
            det.measurements.put("Distance to epi (µm)", epiDistanceMicrons)
        } catch (Exception e) {
            try {
                det.getMeasurementList().addMeasurement("Distance to epi (µm)", epiDistanceMicrons)
            } catch (Exception e2) {
                print("Error updating epi measurement")
            }
        }
    }
}

// Set colors for better visualization
def endoDistClass = getPathClass("Endo Distance")
if (endoDistClass != null) {
    endoDistClass.setColor(255, 255, 0)  // Yellow (R, G, B)
}

def epiDistClass = getPathClass("Epi Distance")
if (epiDistClass != null) {
    epiDistClass.setColor(0, 255, 255)  // Cyan (R, G, B)
}

def endoPointClass = getPathClass("Endo Point")
if (endoPointClass != null) {
    endoPointClass.setColor(255, 0, 0)  // Red (R, G, B)
}

def epiPointClass = getPathClass("Epi Point")
if (epiPointClass != null) {
    epiPointClass.setColor(0, 0, 255)  // Blue (R, G, B)
}

// Update display
fireHierarchyUpdate()
print("Distance measurement complete for " + detections.size() + " detections")
