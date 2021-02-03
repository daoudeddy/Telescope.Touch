/*
 * Copyright 2020 Marco Cipriani (@marcocipriani01) and the Sky Map Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.marcocipriani01.telescopetouch.layers;

import android.content.res.AssetManager;
import android.content.res.Resources;

import io.github.marcocipriani01.telescopetouch.R;

/**
 * An implementation of the {@link AbstractFileBasedLayer} for displaying
 * Messier objects.
 *
 * @author John Taylor
 * @author Brent Bryan
 */
public class MessierLayer extends AbstractFileBasedLayer {

    public static final int DEPTH_ORDER = 10;
    public static final String PREFERENCE_ID = "source_provider.2";

    public MessierLayer(AssetManager assetManager, Resources resources) {
        super(assetManager, resources, "messier.binary");
    }

    @Override
    public int getLayerDepthOrder() {
        return DEPTH_ORDER;
    }

    @Override
    protected int getLayerNameId() {
        return R.string.messier_objects;
    }

    @Override
    public String getPreferenceId() {
        return PREFERENCE_ID;
    }
}