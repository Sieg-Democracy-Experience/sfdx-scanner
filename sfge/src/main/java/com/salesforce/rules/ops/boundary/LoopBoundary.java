package com.salesforce.rules.ops.boundary;

import com.salesforce.graph.vertex.SFVertex;

/** Boundary information for loop detection. */
public class LoopBoundary implements Boundary<SFVertex> {

    private final SFVertex loopVertex;

    public LoopBoundary(SFVertex loopVertex) {
        this.loopVertex = loopVertex;
    }

    @Override
    public SFVertex getBoundaryItem() {
        return loopVertex;
    }

    @Override
    public String toString() {
        return "LoopBoundary{" + "loopVertex=" + loopVertex + '}';
    }
}
