/*
 * Copyright 2021 Marco Cipriani (@marcocipriani01)
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.marcocipriani01.telescopetouch.renderer;

import android.util.Log;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import javax.microedition.khronos.opengles.GL10;

import io.github.marcocipriani01.telescopetouch.R;
import io.github.marcocipriani01.telescopetouch.maths.Vector3;
import io.github.marcocipriani01.telescopetouch.renderer.util.IndexBuffer;
import io.github.marcocipriani01.telescopetouch.renderer.util.NightVisionColorBuffer;
import io.github.marcocipriani01.telescopetouch.renderer.util.SkyRegionMap;
import io.github.marcocipriani01.telescopetouch.renderer.util.TexCoordBuffer;
import io.github.marcocipriani01.telescopetouch.renderer.util.TextureManager;
import io.github.marcocipriani01.telescopetouch.renderer.util.TextureReference;
import io.github.marcocipriani01.telescopetouch.renderer.util.VertexBuffer;
import io.github.marcocipriani01.telescopetouch.source.PointSource;

public class PointObjectManager extends RendererObjectManager {

    private static final int NUM_STARS_IN_TEXTURE = 2;
    // Small sets of point aren't worth breaking up into regions.
    // Right now, I'm arbitrarily setting the threshold to 200.
    private static final int MINIMUM_NUM_POINTS_FOR_REGIONS = 200;
    // Should we compute the regions for the points?
    // If false, we just put them in the catchall region.
    private static final boolean COMPUTE_REGIONS = true;
    private final SkyRegionMap<RegionData> skyRegions = new SkyRegionMap<>();
    private int numPoints = 0;
    private TextureReference textureRef = null;

    public PointObjectManager(int layer, TextureManager textureManager) {
        super(layer, textureManager);
        // We want to initialize the labels of a sky region to an empty set of data.
        skyRegions.setRegionDataFactory(RegionData::new);
    }

    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    public synchronized void updateObjects(List<PointSource> points, EnumSet<UpdateType> updateType) {
        synchronized (points) {
            if (updateType.contains(UpdateType.UpdatePositions)) {
                // Sanity check: make sure the number of points is unchanged.
                if (points.size() != numPoints) {
                    Log.e("PointObjectManager", "Updating PointObjectManager a different number of points: update had " +
                            points.size() + " vs " + numPoints + " before");
                    return;
                }
            } else if (!updateType.contains(UpdateType.Reset)) {
                return;
            }

            numPoints = points.size();
            skyRegions.clear();

            if (COMPUTE_REGIONS) {
                // Find the region for each point, and put it in a separate list for that region.
                for (PointSource point : points) {
                    skyRegions.getRegionData(points.size() < MINIMUM_NUM_POINTS_FOR_REGIONS ?
                            SkyRegionMap.CATCHALL_REGION_ID : SkyRegionMap.getObjectRegion(point.getLocation())).sources.add(point);
                }
            } else {
                skyRegions.getRegionData(SkyRegionMap.CATCHALL_REGION_ID).sources = points;
            }
        }

        // Generate the resources for all of the regions.
        for (RegionData data : skyRegions.getDataForAllRegions()) {
            int numVertices = 4 * data.sources.size();
            int numIndices = 6 * data.sources.size();

            data.vertexBuffer.reset(numVertices);
            data.colorBuffer.reset(numVertices);
            data.texCoordBuffer.reset(numVertices);
            data.indexBuffer.reset(numIndices);

            Vector3 up = new Vector3(0, 1, 0);

            // By inspecting the perspective projection matrix, you can show that,
            // to have a quad at the center of the screen to be of size k by k
            // pixels, the width and height are both:
            // k * tan(fovy / 2) / screenHeight
            // This is not difficult to derive.  Look at the transformation matrix
            // in SkyRenderer if you're interested in seeing why this is true.
            // I'm arbitrarily deciding that at a 60 degree field of view, and 480
            // pixels high, a size of 1 means "1 pixel," so calculate sizeFactor
            // based on this.  These numbers mostly come from the fact that that's
            // what I think looks reasonable.
            float fovyInRadians = 60 * (float) Math.PI / 180.0f;
            float sizeFactor = (float) Math.sin(fovyInRadians * 0.5f) / (float) Math.cos(fovyInRadians * 0.5f) / 480;

            Vector3 bottomLeftPos = new Vector3();
            Vector3 topLeftPos = new Vector3();
            Vector3 bottomRightPos = new Vector3();
            Vector3 topRightPos = new Vector3();

            Vector3 su = new Vector3();
            Vector3 sv = new Vector3();

            short index = 0;

            float starWidthInTexels = 1.0f / NUM_STARS_IN_TEXTURE;

            for (PointSource p : data.sources) {
                int color = 0xff000000 | p.getColor();  // Force alpha to 0xff
                short bottomLeft = index++;
                short topLeft = index++;
                short bottomRight = index++;
                short topRight = index++;

                // First triangle
                data.indexBuffer.addIndex(bottomLeft);
                data.indexBuffer.addIndex(topLeft);
                data.indexBuffer.addIndex(bottomRight);

                // Second triangle
                data.indexBuffer.addIndex(topRight);
                data.indexBuffer.addIndex(bottomRight);
                data.indexBuffer.addIndex(topLeft);

                data.texCoordBuffer.addTexCoords(0, 1);
                data.texCoordBuffer.addTexCoords(0, 0);
                data.texCoordBuffer.addTexCoords(starWidthInTexels, 1);
                data.texCoordBuffer.addTexCoords(starWidthInTexels, 0);

                Vector3 pos = p.getLocation();
                Vector3 u = Vector3.normalized(Vector3.vectorProduct(pos, up));
                Vector3 v = Vector3.vectorProduct(u, pos);

                float s = p.getSize() * sizeFactor;

                su.assign(s * u.x, s * u.y, s * u.z);
                sv.assign(s * v.x, s * v.y, s * v.z);

                bottomLeftPos.assign(pos.x - su.x - sv.x, pos.y - su.y - sv.y, pos.z - su.z - sv.z);
                topLeftPos.assign(pos.x - su.x + sv.x, pos.y - su.y + sv.y, pos.z - su.z + sv.z);
                bottomRightPos.assign(pos.x + su.x - sv.x, pos.y + su.y - sv.y, pos.z + su.z - sv.z);
                topRightPos.assign(pos.x + su.x + sv.x, pos.y + su.y + sv.y, pos.z + su.z + sv.z);

                // Add the vertices
                data.vertexBuffer.addPoint(bottomLeftPos);
                data.colorBuffer.addColor(color);

                data.vertexBuffer.addPoint(topLeftPos);
                data.colorBuffer.addColor(color);

                data.vertexBuffer.addPoint(bottomRightPos);
                data.colorBuffer.addColor(color);

                data.vertexBuffer.addPoint(topRightPos);
                data.colorBuffer.addColor(color);
            }
            data.sources = null;
        }
    }

    @Override
    public void reload(GL10 gl, boolean fullReload) {
        textureRef = textureManager().getTextureFromResource(gl, R.drawable.stars_texture);
        for (RegionData data : skyRegions.getDataForAllRegions()) {
            data.vertexBuffer.reload();
            data.colorBuffer.reload();
            data.texCoordBuffer.reload();
            data.indexBuffer.reload();
        }
    }

    @Override
    protected void drawInternal(GL10 gl) {
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glEnableClientState(GL10.GL_COLOR_ARRAY);
        gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);

        gl.glEnable(GL10.GL_CULL_FACE);
        gl.glFrontFace(GL10.GL_CW);
        gl.glCullFace(GL10.GL_BACK);

        gl.glEnable(GL10.GL_ALPHA_TEST);
        gl.glAlphaFunc(GL10.GL_GREATER, 0.5f);

        gl.glEnable(GL10.GL_TEXTURE_2D);

        textureRef.bind(gl);

        gl.glTexEnvf(GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_MODE, GL10.GL_MODULATE);

        // Render all of the active sky regions.
        SkyRegionMap.ActiveRegionData activeRegions = getRenderState().getActiveSkyRegions();
        ArrayList<RegionData> activeRegionData = skyRegions.getDataForActiveRegions(activeRegions);
        for (RegionData data : activeRegionData) {
            if (data.vertexBuffer.size() == 0) {
                continue;
            }

            data.vertexBuffer.set(gl);
            data.colorBuffer.set(gl, getRenderState().getNightVisionMode());
            data.texCoordBuffer.set(gl);
            data.indexBuffer.draw(gl, GL10.GL_TRIANGLES);
        }

        gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
        gl.glDisable(GL10.GL_TEXTURE_2D);
        gl.glDisable(GL10.GL_ALPHA_TEST);
    }

    private static class RegionData {
        private final VertexBuffer vertexBuffer = new VertexBuffer(true);
        private final NightVisionColorBuffer colorBuffer = new NightVisionColorBuffer(true);
        private final TexCoordBuffer texCoordBuffer = new TexCoordBuffer(true);
        private final IndexBuffer indexBuffer = new IndexBuffer(true);
        // TODO(jpowell): This is a convenient hack until the catalog tells us the
        // region for all of its sources.  Remove this once we add that.
        List<PointSource> sources = new ArrayList<>();
    }
}