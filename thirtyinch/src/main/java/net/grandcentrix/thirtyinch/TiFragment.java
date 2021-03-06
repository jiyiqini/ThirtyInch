/*
 * Copyright (C) 2016 grandcentrix GmbH
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.grandcentrix.thirtyinch;

import net.grandcentrix.thirtyinch.callonmainthread.CallOnMainThreadInterceptor;
import net.grandcentrix.thirtyinch.distinctuntilchanged.DistinctUntilChangedInterceptor;
import net.grandcentrix.thirtyinch.internal.InterceptableViewBinder;
import net.grandcentrix.thirtyinch.internal.PresenterSavior;
import net.grandcentrix.thirtyinch.internal.PresenterViewBinder;
import net.grandcentrix.thirtyinch.internal.TiPresenterLogger;
import net.grandcentrix.thirtyinch.internal.TiPresenterProvider;
import net.grandcentrix.thirtyinch.internal.TiViewProvider;
import net.grandcentrix.thirtyinch.util.AndroidDeveloperOptions;
import net.grandcentrix.thirtyinch.util.AnnotationUtil;

import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.List;

public abstract class TiFragment<P extends TiPresenter<V>, V extends TiView>
        extends Fragment implements TiPresenterProvider<P>, TiPresenterLogger,
        TiViewProvider<V>, InterceptableViewBinder<V> {

    private static final String SAVED_STATE_PRESENTER_ID = "presenter_id";

    private final String TAG = this.getClass().getSimpleName()
            + ":" + TiFragment.class.getSimpleName()
            + "@" + Integer.toHexString(this.hashCode());

    private volatile boolean mActivityStarted = false;

    private P mPresenter;

    private String mPresenterId;

    private PresenterViewBinder<V> mViewBinder = new PresenterViewBinder<>(this);

    @NonNull
    @Override
    public Removable addBindViewInterceptor(@NonNull final BindViewInterceptor interceptor) {
        return mViewBinder.addBindViewInterceptor(interceptor);
    }

    @Nullable
    @Override
    public V getInterceptedViewOf(@NonNull final BindViewInterceptor interceptor) {
        return mViewBinder.getInterceptedViewOf(interceptor);
    }

    @NonNull
    @Override
    public List<BindViewInterceptor> getInterceptors(
            @NonNull final Filter<BindViewInterceptor> predicate) {
        return mViewBinder.getInterceptors(predicate);
    }

    public P getPresenter() {
        return mPresenter;
    }

    /**
     * Invalidates the cache of the latest bound view. Forces the next binding of the view to run
     * through all the interceptors (again).
     */
    @Override
    public void invalidateView() {
        mViewBinder.invalidateView();
    }

    @Override
    public void logTiMessages(final String msg) {
        Log.v(TAG, msg);
    }

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);
        Log.v(TAG, "onAttach()");

    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate(" + savedInstanceState + ")");

        if (mPresenter == null && savedInstanceState != null) {
            // recover with Savior
            // this should always work.
            final String recoveredPresenterId = savedInstanceState
                    .getString(SAVED_STATE_PRESENTER_ID);
            if (recoveredPresenterId != null) {
                Log.d(TAG, "try to recover Presenter with id: " + recoveredPresenterId);
                //noinspection unchecked
                mPresenter = (P) PresenterSavior.INSTANCE.recover(recoveredPresenterId);
                if (mPresenter != null) {
                    // save recovered presenter with new id. No other instance of this activity,
                    // holding the presenter before, is now able to remove the reference to
                    // this presenter from the savior
                    PresenterSavior.INSTANCE.free(recoveredPresenterId);
                    mPresenterId = PresenterSavior.INSTANCE.safe(mPresenter);
                }
                Log.d(TAG, "recovered Presenter " + mPresenter);
            }
        }

        if (mPresenter == null) {
            mPresenter = providePresenter();
            Log.d(TAG, "created Presenter: " + mPresenter);
            final TiConfiguration config = mPresenter.getConfig();
            if (config.shouldRetainPresenter() && config.useStaticSaviorToRetain()) {
                mPresenterId = PresenterSavior.INSTANCE.safe(mPresenter);
            }
            mPresenter.create();
        }

        final TiConfiguration config = mPresenter.getConfig();
        if (config.isCallOnMainThreadInterceptorEnabled()) {
            addBindViewInterceptor(new CallOnMainThreadInterceptor());
        }

        if (config.isDistinctUntilChangedInterceptorEnabled()) {
            addBindViewInterceptor(new DistinctUntilChangedInterceptor());
        }

        if (config.shouldRetainPresenter()) {
            setRetainInstance(true);
        }
    }

    @Nullable
    @Override
    public View onCreateView(final LayoutInflater inflater, @Nullable final ViewGroup container,
            @Nullable final Bundle savedInstanceState) {
        mViewBinder.invalidateView();
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        final FragmentActivity activity = getActivity();
        Log.v(TAG, "onDestroy() recreating=" + !activity.isFinishing());

        boolean destroyPresenter = false;
        if (activity.isFinishing()) {
            // Probably a backpress and not a configuration change
            // Activity will not be recreated and finally destroyed, also destroyed the presenter
            destroyPresenter = true;
        }

        final TiConfiguration config = mPresenter.getConfig();
        if (!destroyPresenter &&
                !config.shouldRetainPresenter()) {
            // configuration says the presenter should not be retained, a new presenter instance
            // will be created and the current presenter should be destroyed
            destroyPresenter = true;
        }

        if (!destroyPresenter &&
                !config.useStaticSaviorToRetain()
                && AndroidDeveloperOptions.isDontKeepActivitiesEnabled(getActivity())) {
            // configuration says the PresenterSavior should not be used. Retaining the presenter
            // relays on the Activity nonConfigurationInstance which is always null when
            // "don't keep activities" is enabled.
            // a new presenter instance will be created and the current presenter should be destroyed
            destroyPresenter = true;
        }

        if (destroyPresenter) {
            mPresenter.destroy();
            PresenterSavior.INSTANCE.free(mPresenterId);
        }
    }

    @Override
    public void onDestroyView() {
        Log.v(TAG, "onDestroyView()");
        mPresenter.sleep();
        super.onDestroyView();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        Log.v(TAG, "onDetach()");
    }

    @Override
    public void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(SAVED_STATE_PRESENTER_ID, mPresenterId);
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.v(TAG, "onStart()");
        mActivityStarted = true;

        if (isUiPossible()) {
            mViewBinder.bindView(mPresenter, this);
            getActivity().getWindow().getDecorView().post(new Runnable() {
                @Override
                public void run() {
                    if (isUiPossible() && mActivityStarted) {
                        mPresenter.wakeUp();
                    }
                }
            });
        }
    }

    @Override
    public void onStop() {
        Log.v(TAG, "onStop()");
        mActivityStarted = false;
        mPresenter.sleep();
        super.onStop();
    }

    /**
     * the default implementation assumes that the fragment is the view and implements the {@link
     * TiView} interface. Override this method for a different behaviour.
     *
     * @return the object implementing the TiView interface
     */
    @NonNull
    public V provideView() {

        final Class<?> foundViewInterface = AnnotationUtil
                .getInterfaceOfClassExtendingGivenInterface(this.getClass(), TiView.class);

        if (foundViewInterface == null) {
            throw new IllegalArgumentException(
                    "This Fragment doesn't implement a TiView interface. "
                            + "This is the default behaviour. Override provideView() to explicitly change this.");
        } else {
            if (foundViewInterface.getSimpleName().equals("TiView")) {
                throw new IllegalArgumentException(
                        "extending TiView doesn't make sense, it's an empty interface."
                                + " This is the default behaviour. Override provideView() to explicitly change this.");
            } else {
                // assume that the fragment itself is the view and implements the TiView interface
                //noinspection unchecked
                return (V) this;
            }
        }
    }

    @Override
    public String toString() {
        String presenter = getPresenter() == null ? "null" :
                getPresenter().getClass().getSimpleName()
                        + "@" + Integer.toHexString(getPresenter().hashCode());

        return getClass().getSimpleName()
                + ":" + TiFragment.class.getSimpleName()
                + "@" + Integer.toHexString(hashCode())
                + "{presenter=" + presenter + "}";
    }

    private boolean isUiPossible() {
        return isAdded() && !isDetached();
    }
}