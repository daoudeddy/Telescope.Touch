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

package io.github.marcocipriani01.telescopetouch;

import android.app.Application;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;

import io.github.marcocipriani01.telescopetouch.indi.ConnectionManager;
import io.github.marcocipriani01.telescopetouch.phd2.PHD2Client;

/**
 * The main application class.
 *
 * @author marcocipriani01
 */
public class TelescopeTouchApp extends Application {

    /**
     * Global connection manager.
     */
    public static final ConnectionManager connectionManager = new ConnectionManager();
    public static final NSDHelper nsdHelper = new NSDHelper();
    public static final PHD2Client phd2 = new PHD2Client();
    public static Session session;
    public static ChannelSftp channel;
    private ApplicationComponent component;

    /**
     * Returns the Tag for a class to be used in Android logging statements
     */
    public static String getTag(Class<?> clazz) {
        return ApplicationConstants.APP_NAME + "." + clazz.getSimpleName();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        component = DaggerApplicationComponent.builder()
                .applicationModule(new ApplicationModule(this)).build();
        connectionManager.init(this);
    }

    public ApplicationComponent getApplicationComponent() {
        return component;
    }
}