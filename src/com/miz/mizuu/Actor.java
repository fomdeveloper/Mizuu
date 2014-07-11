/*
 * Copyright (C) 2014 Michell Bak
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.miz.mizuu;

import java.util.ArrayList;

import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.ActionBar;
import android.app.ActionBar.OnNavigationListener;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.miz.base.MizActivity;
import com.miz.functions.ActionBarSpinner;
import com.miz.functions.MizLib;
import com.miz.functions.SpinnerItem;
import com.miz.mizuu.fragments.ActorBiographyFragment;
import com.miz.mizuu.fragments.ActorMoviesFragment;
import com.miz.mizuu.fragments.ActorPhotosFragment;
import com.miz.mizuu.fragments.ActorTaggedPhotosFragment;
import com.miz.mizuu.fragments.ActorTvShowsFragment;

public class Actor extends MizActivity implements OnNavigationListener {

	private ViewPager awesomePager;
	private String actorId, actorName, json, baseUrl, mActorThumb, mTmdbApiKey;
	private ArrayList<SpinnerItem> spinnerItems = new ArrayList<SpinnerItem>();
	private ActionBarSpinner spinnerAdapter;
	private ActionBar actionBar;
	private ProgressBar pbar;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (isFullscreen())
			setTheme(R.style.Mizuu_Theme_Transparent_NoBackGround_FullScreen);
		else
			setTheme(R.style.Mizuu_Theme_NoBackGround_Transparent);

		getWindow().requestFeature(Window.FEATURE_ACTION_BAR_OVERLAY);

		setContentView(R.layout.viewpager);

		findViewById(R.id.layout).setBackgroundResource(R.drawable.bg);

		pbar = (ProgressBar) findViewById(R.id.progressbar);
		pbar.setVisibility(View.VISIBLE);

		actorId = getIntent().getExtras().getString("actorID");
		actorName = getIntent().getExtras().getString("actorName");
		mActorThumb = getIntent().getExtras().getString("thumb");
		mTmdbApiKey = MizLib.getTmdbApiKey(this);

		setTitle(actorName);

		if (MizLib.isPortrait(this)) {
			getWindow().setBackgroundDrawableResource(R.drawable.bg);
		}
		
		awesomePager = (ViewPager) findViewById(R.id.awesomepager);
		awesomePager.setOffscreenPageLimit(3);
		awesomePager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
			@Override
			public void onPageSelected(int position) {
				actionBar.setSelectedNavigationItem(position);
			}
		});

		if (savedInstanceState != null) {	
			json = savedInstanceState.getString("json", "");
			baseUrl = savedInstanceState.getString("baseUrl");
			mActorThumb = savedInstanceState.getString("mActorThumb");
			setupActionBarStuff();

			awesomePager.setCurrentItem(savedInstanceState.getInt("tab", 0));
		} else {
			new ActorLoader().execute(actorId);
		}
	}

	private void setupSpinnerItems() {
		spinnerItems.clear();
		spinnerItems.add(new SpinnerItem(actorName, getString(R.string.actorBiography), ActorBiographyFragment.newInstance(json, mActorThumb)));
		spinnerItems.add(new SpinnerItem(actorName, getString(R.string.chooserMovies), ActorMoviesFragment.newInstance(json, baseUrl)));

		try {
			JSONObject j = new JSONObject(json);
			if (j.getJSONObject("tv_credits").getJSONArray("cast").length() > 0) {
				spinnerItems.add(new SpinnerItem(actorName, getString(R.string.chooserTVShows), ActorTvShowsFragment.newInstance(json, baseUrl)));
			}
		} catch (JSONException e) {}

		try {
			JSONObject j = new JSONObject(json);
			if (j.getJSONObject("images").getJSONArray("profiles").length() > 0)
				spinnerItems.add(new SpinnerItem(actorName, getString(R.string.actorsShowAllPhotos), ActorPhotosFragment.newInstance(json, actorName, baseUrl)));
		} catch (JSONException e) {}

		try {
			JSONObject j = new JSONObject(json);
			if (j.getJSONObject("tagged_images").getInt("total_results") > 0)
				spinnerItems.add(new SpinnerItem(actorName, getString(R.string.actorsTaggedPhotos), ActorTaggedPhotosFragment.newInstance(json, actorName, baseUrl)));
		} catch (JSONException e) {}

		actionBar.setListNavigationCallbacks(spinnerAdapter, this);
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt("tab", awesomePager.getCurrentItem());
		outState.putString("json", json);
		outState.putString("baseUrl", baseUrl);
		outState.putString("mActorThumb", mActorThumb);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case android.R.id.home:
			onBackPressed();
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		getActionBar().setDisplayHomeAsUpEnabled(true);
	}

	private class ActorDetailsAdapter extends FragmentPagerAdapter {

		public ActorDetailsAdapter(FragmentManager fm) {
			super(fm);
		}

		@Override
		public Fragment getItem(int index) {
			return spinnerItems.get(index).getFragment();
		}  

		@Override  
		public int getCount() {
			return spinnerItems.size();
		}
	}

	@Override
	public boolean onNavigationItemSelected(int itemPosition, long itemId) {
		awesomePager.setCurrentItem(itemPosition);
		return true;
	}

	private class ActorLoader extends AsyncTask<Object, Object, String> {
		@Override
		protected String doInBackground(Object... params) {
			try {				
				HttpClient httpclient = new DefaultHttpClient();
				HttpGet httppost = new HttpGet("https://api.themoviedb.org/3/configuration?api_key=" + mTmdbApiKey);
				httppost.setHeader("Accept", "application/json");
				ResponseHandler<String> responseHandler = new BasicResponseHandler();
				baseUrl = httpclient.execute(httppost, responseHandler);

				JSONObject jObject = new JSONObject(baseUrl);
				try { baseUrl = jObject.getJSONObject("images").getString("base_url");
				} catch (Exception e) { baseUrl = MizLib.TMDB_BASE_URL; }

				httppost = new HttpGet("https://api.themoviedb.org/3/person/" + params[0] + "?api_key=" + mTmdbApiKey + "&append_to_response=movie_credits,tv_credits,images,tagged_images");
				httppost.setHeader("Accept", "application/json");
				responseHandler = new BasicResponseHandler();

				json = httpclient.execute(httppost, responseHandler);

				return json;
			} catch (Exception e) {} // If the fragment is no longer attached to the Activity

			return null;
		}

		@Override
		protected void onPostExecute(String result) {
			if (result != null) {
				setupActionBarStuff();
			} else {
				Toast.makeText(getApplicationContext(), R.string.errorSomethingWentWrong, Toast.LENGTH_SHORT).show();
			}
		}
	}

	private void setupActionBarStuff() {
		actionBar = getActionBar();
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
		if (spinnerAdapter == null)
			spinnerAdapter = new ActionBarSpinner(getApplicationContext(), spinnerItems);

		setTitle(null);

		if (!MizLib.isPortrait(getApplicationContext()))
			findViewById(R.id.layout).setBackgroundResource(0);
		pbar.setVisibility(View.GONE);

		setupSpinnerItems();

		awesomePager.setAdapter(new ActorDetailsAdapter(getSupportFragmentManager()));

		findViewById(R.id.layout).setBackgroundResource(0);
	}
}