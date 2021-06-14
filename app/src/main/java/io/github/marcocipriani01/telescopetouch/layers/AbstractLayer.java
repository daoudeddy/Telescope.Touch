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

package io.github.marcocipriani01.telescopetouch.layers;

import android.content.res.Resources;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import io.github.marcocipriani01.telescopetouch.TelescopeTouchApp;
import io.github.marcocipriani01.telescopetouch.astronomy.GeocentricCoordinates;
import io.github.marcocipriani01.telescopetouch.control.AstronomerModel;
import io.github.marcocipriani01.telescopetouch.renderer.RendererController;
import io.github.marcocipriani01.telescopetouch.renderer.RendererController.AtomicSection;
import io.github.marcocipriani01.telescopetouch.renderer.RendererControllerBase;
import io.github.marcocipriani01.telescopetouch.renderer.RendererControllerBase.RenderManager;
import io.github.marcocipriani01.telescopetouch.renderer.RendererObjectManager.UpdateType;
import io.github.marcocipriani01.telescopetouch.renderer.util.AbstractUpdateClosure;
import io.github.marcocipriani01.telescopetouch.renderer.util.UpdateClosure;
import io.github.marcocipriani01.telescopetouch.search.PrefixStore;
import io.github.marcocipriani01.telescopetouch.search.SearchResult;
import io.github.marcocipriani01.telescopetouch.source.AstronomicalSource;
import io.github.marcocipriani01.telescopetouch.source.ImageSource;
import io.github.marcocipriani01.telescopetouch.source.LineSource;
import io.github.marcocipriani01.telescopetouch.source.PointSource;
import io.github.marcocipriani01.telescopetouch.source.TextSource;

/**
 * Base implementation of the {@link Layer} interface.
 *
 * @author John Taylor
 * @author Brent Bryan
 */
public abstract class AbstractLayer implements Layer {

    private static final String TAG = TelescopeTouchApp.getTag(AbstractLayer.class);
    private final ReentrantLock renderMapLock = new ReentrantLock();
    private final HashMap<Class<?>, RenderManager<?>> renderMap = new HashMap<>();
    private final Resources resources;
    private final List<TextSource> textSources = Collections.synchronizedList(new ArrayList<>());
    private final List<ImageSource> imageSources = Collections.synchronizedList(new ArrayList<>());
    private final List<PointSource> pointSources = Collections.synchronizedList(new ArrayList<>());
    private final List<LineSource> lineSources = Collections.synchronizedList(new ArrayList<>());
    private final List<AstronomicalSource> astroSources = Collections.synchronizedList(new ArrayList<>());
    private final boolean shouldUpdate;
    private final HashMap<String, SearchResult> searchIndex = new HashMap<>();
    private final PrefixStore prefixStore = new PrefixStore();
    private RendererController renderer;
    private AbstractLayer.SourceUpdateClosure closure;

    public AbstractLayer(Resources resources, boolean shouldUpdate) {
        this.resources = resources;
        this.shouldUpdate = shouldUpdate;
    }

    @Override
    public void initialize() {
        astroSources.clear();
        initializeAstroSources(astroSources);
        for (AstronomicalSource astroSource : astroSources) {
            AstronomicalSource sources = astroSource.initialize();
            synchronized (textSources) {
                textSources.addAll(sources.getLabels());
            }
            synchronized (imageSources) {
                imageSources.addAll(sources.getImages());
            }
            synchronized (pointSources) {
                pointSources.addAll(sources.getPoints());
            }
            synchronized (lineSources) {
                lineSources.addAll(sources.getLines());
            }
            List<String> names = astroSource.getNames();
            if (!names.isEmpty()) {
                GeocentricCoordinates searchLoc = astroSource.getSearchLocation();
                for (String name : names) {
                    searchIndex.put(name.toLowerCase(), new SearchResult(name, searchLoc));
                    prefixStore.add(name.toLowerCase());
                }
            }
        }
        // update the renderer
        updateLayerForControllerChange();
    }

    /**
     * Subclasses should override this method and add all their
     * {@link AstronomicalSource} to the given {@link ArrayList}.
     */
    protected abstract void initializeAstroSources(List<AstronomicalSource> sources);

    /**
     * Redraws the sources on this layer, after first refreshing them based on
     * the current state of the
     * {@link AstronomerModel}.
     */
    protected void refreshSources() {
        refreshSources(EnumSet.noneOf(UpdateType.class));
    }

    /**
     * Redraws the sources on this layer, after first refreshing them based on
     * the current state of the
     * {@link AstronomerModel}.
     */
    protected synchronized void refreshSources(EnumSet<UpdateType> updateTypes) {
        for (AstronomicalSource astroSource : astroSources) {
            updateTypes.addAll(astroSource.update());
        }

        if (!updateTypes.isEmpty()) {
            redraw(updateTypes);
        }
    }

    protected Resources getResources() {
        return resources;
    }

    @Override
    public void registerWithRenderer(RendererController rendererController) {
        this.renderMap.clear();
        this.renderer = rendererController;
        updateLayerForControllerChange();
    }

    protected void updateLayerForControllerChange() {
        refreshSources(EnumSet.of(UpdateType.Reset));
        if (shouldUpdate) {
            if (closure == null) {
                closure = new SourceUpdateClosure(this);
            }
            addUpdateClosure(closure);
        }
    }

    @Override
    public void setVisible(boolean visible) {
        renderMapLock.lock();
        try {
            if (renderer == null) {
                Log.w(TAG, "Renderer not set - aborting " + this.getClass().getSimpleName());
                return;
            }

            AtomicSection atomic = renderer.createAtomic();
            for (Entry<Class<?>, RenderManager<?>> entry : renderMap.entrySet()) {
                entry.getValue().queueEnabled(visible, atomic);
            }
            renderer.queueAtomic(atomic);
        } finally {
            renderMapLock.unlock();
        }
    }

    protected void addUpdateClosure(UpdateClosure closure) {
        if (renderer != null)
            renderer.addUpdateClosure(closure);
    }

    /**
     * Updates the renderer (using the given {@link UpdateType}), with then given set of
     * UI elements.  Depending on the value of {@link UpdateType}, current sources will
     * either have their state updated, or will be overwritten by the given set
     * of UI elements.
     */
    private void redraw(EnumSet<UpdateType> updateTypes) {
        // Log.d(TAG, getLayerName() + " Updating renderer: " + updateTypes);
        if (renderer == null) {
            Log.w(TAG, "Renderer not set - aborting: " + this.getClass().getSimpleName());
            return;
        }

        renderMapLock.lock();
        try {
            // Blog.d(this, "Redraw: " + updateTypes);
            AtomicSection atomic = renderer.createAtomic();
            setSources(textSources, updateTypes, TextSource.class, atomic);
            setSources(pointSources, updateTypes, PointSource.class, atomic);
            setSources(lineSources, updateTypes, LineSource.class, atomic);
            setSources(imageSources, updateTypes, ImageSource.class, atomic);
            renderer.queueAtomic(atomic);
        } finally {
            renderMapLock.unlock();
        }
    }

    /**
     * Sets the objects on the {@link RenderManager} to the given values,
     * creating (or disabling) the {@link RenderManager} if necessary.
     */
    @SuppressWarnings("unchecked")
    private <E> void setSources(List<E> sources, EnumSet<UpdateType> updateType, Class<E> clazz, AtomicSection atomic) {
        RenderManager<E> manager = (RenderManager<E>) renderMap.get(clazz);
        if (sources == null || sources.isEmpty()) {
            if (manager != null) {
                // TODO(brent): we should really just disable this layer, but in a manner that it will automatically be re-enabled when appropriate.
                manager.queueObjects(Collections.emptyList(), updateType, atomic);
            }
            return;
        }
        if (manager == null) {
            manager = createRenderManager(clazz, atomic);
            renderMap.put(clazz, manager);
        }
        // Blog.d(this, "       " + clazz.getSimpleName() + " " + sources.size());
        manager.queueObjects(sources, updateType, atomic);
    }

    @SuppressWarnings("unchecked")
    <E> RenderManager<E> createRenderManager(Class<E> clazz, RendererControllerBase controller) {
        if (clazz == ImageSource.class) {
            return (RenderManager<E>) controller.createImageManager(getLayerDepthOrder());
        } else if (clazz == TextSource.class) {
            return (RenderManager<E>) controller.createLabelManager(getLayerDepthOrder());
        } else if (clazz == LineSource.class) {
            return (RenderManager<E>) controller.createLineManager(getLayerDepthOrder());
        } else if (clazz == PointSource.class) {
            return (RenderManager<E>) controller.createPointManager(getLayerDepthOrder());
        }
        throw new IllegalStateException("Unknown source type: " + clazz);
    }

    @Override
    public List<SearchResult> searchByObjectName(String name) {
        Log.d(TAG, "Search planets layer for " + name);
        List<SearchResult> matches = new ArrayList<>();
        SearchResult searchResult = searchIndex.get(name.toLowerCase());
        if (searchResult != null) matches.add(searchResult);
        Log.d(TAG, getLayerName() + " provided " + matches.size() + " results for " + name);
        return matches;
    }

    @Override
    public Set<String> getObjectNamesMatchingPrefix(String prefix) {
        Log.d(TAG, "Searching planets layer for prefix " + prefix);
        Set<String> results = prefixStore.queryByPrefix(prefix);
        Log.d(TAG, "Got " + results.size() + " results for prefix " + prefix + " in " + getLayerName());
        return results;
    }

    /**
     * Provides a string ID to the internationalized name of this layer.
     */
    protected abstract int getLayerNameId();

    @Override
    public String getPreferenceId() {
        return "source_provider." + getLayerNameId();
    }

    @Override
    public String getLayerName() {
        return resources.getString(getLayerNameId());
    }

    /**
     * Implementation of the {@link UpdateClosure} interface used to update a layer
     */
    public static class SourceUpdateClosure extends AbstractUpdateClosure {

        private final AbstractLayer layer;

        public SourceUpdateClosure(AbstractLayer layer) {
            this.layer = layer;
        }

        @Override
        public void run() {
            layer.refreshSources();
        }
    }
}