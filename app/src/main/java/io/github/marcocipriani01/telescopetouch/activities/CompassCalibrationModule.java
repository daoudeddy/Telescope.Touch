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

package io.github.marcocipriani01.telescopetouch.activities;

import android.content.Context;

import dagger.Module;
import dagger.Provides;
import io.github.marcocipriani01.telescopetouch.inject.PerActivity;

/**
 * @author johntaylor
 */
@Module
public class CompassCalibrationModule {

    private final CompassCalibrationActivity activity;

    public CompassCalibrationModule(CompassCalibrationActivity activity) {
        this.activity = activity;
    }

    @Provides
    @PerActivity
    Context provideContext() {
        return activity;
    }
}