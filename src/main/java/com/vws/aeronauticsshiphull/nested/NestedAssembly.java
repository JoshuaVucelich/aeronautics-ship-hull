package com.vws.aeronauticsshiphull.nested;

import java.util.UUID;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Bearing/crane on deck: child body whose world pose is parentPose ∘ localOffset.
 * Child may be another ServerSubLevel UUID or a KinematicContraption id string.
 */
public final class NestedAssembly {
    public final UUID childId;
    public final Vector3d localPos = new Vector3d();
    public final Quaterniond localRot = new Quaterniond();
    public final String label;

    public NestedAssembly(UUID childId, String label) {
        this.childId = childId;
        this.label = label == null ? "nested" : label;
    }

    public void setLocal(double x, double y, double z, Quaterniond rot) {
        localPos.set(x, y, z);
        if (rot != null) localRot.set(rot);
    }
}
