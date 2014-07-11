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

import static com.miz.functions.PreferenceKeys.ALWAYS_DELETE_FILE;
import static com.miz.functions.PreferenceKeys.DISABLE_ETHERNET_WIFI_CHECK;
import static com.miz.functions.PreferenceKeys.IGNORED_FILES_ENABLED;
import static com.miz.functions.PreferenceKeys.IGNORED_NFO_FILES;
import static com.miz.functions.PreferenceKeys.IGNORED_TITLE_PREFIXES;
import static com.miz.functions.PreferenceKeys.IGNORE_VIDEO_FILE_TYPE;
import static com.miz.functions.PreferenceKeys.REMOVE_MOVIES_FROM_WATCHLIST;
import static com.miz.functions.PreferenceKeys.BUFFER_SIZE;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbFile;

import org.json.JSONArray;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.ActionBar.OnNavigationListener;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.ViewPager;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.youtube.player.YouTubeApiServiceUtil;
import com.google.android.youtube.player.YouTubeInitializationResult;
import com.google.android.youtube.player.YouTubeStandalonePlayer;
import com.miz.base.MizActivity;
import com.miz.db.DbAdapter;
import com.miz.functions.ActionBarSpinner;
import com.miz.functions.MizLib;
import com.miz.functions.Movie;
import com.miz.functions.MovieVersion;
import com.miz.functions.SpinnerItem;
import com.miz.mizuu.fragments.ActorBrowserFragment;
import com.miz.mizuu.fragments.MovieDetailsFragment;
import com.miz.service.DeleteFile;
import com.miz.service.MakeAvailableOffline;
import com.miz.smbstreamer.Streamer;
import com.miz.widgets.MovieBackdropWidgetProvider;
import com.miz.widgets.MovieCoverWidgetProvider;
import com.miz.widgets.MovieStackWidgetProvider;

public class MovieDetails extends MizActivity implements OnNavigationListener {

	private ViewPager awesomePager;
	private int movieId;
	private Movie thisMovie;
	private DbAdapter db;
	private boolean ignorePrefixes, prefsRemoveMoviesFromWatchlist, ignoreDeletedFiles, ignoreNfo, useWildcard, prefsDisableEthernetWiFiCheck;
	private ArrayList<SpinnerItem> spinnerItems = new ArrayList<SpinnerItem>();
	private ActionBarSpinner spinnerAdapter;
	private ActionBar actionBar;
	private String mYouTubeApiKey;
	private long videoPlaybackStarted, videoPlaybackEnded;
	private Context mContext;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		mContext = this;
		
		if (isFullscreen())
			setTheme(R.style.Mizuu_Theme_Transparent_NoBackGround_FullScreen);
		else
			setTheme(R.style.Mizuu_Theme_NoBackGround_Transparent);

		if (MizLib.isPortrait(this)) {
			getWindow().setBackgroundDrawableResource(R.drawable.bg);
		}

		getWindow().requestFeature(Window.FEATURE_ACTION_BAR_OVERLAY);

		setContentView(R.layout.viewpager);

		actionBar = getActionBar();
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
		if (spinnerAdapter == null)
			spinnerAdapter = new ActionBarSpinner(this, spinnerItems);

		setTitle(null);

		ignorePrefixes = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(IGNORED_TITLE_PREFIXES, false);
		ignoreNfo = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(IGNORED_NFO_FILES, true);
		prefsRemoveMoviesFromWatchlist = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(REMOVE_MOVIES_FROM_WATCHLIST, true);
		ignoreDeletedFiles = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(IGNORED_FILES_ENABLED, false);
		useWildcard = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(IGNORE_VIDEO_FILE_TYPE, false);
		prefsDisableEthernetWiFiCheck = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(DISABLE_ETHERNET_WIFI_CHECK, false);

		mYouTubeApiKey = MizLib.getYouTubeApiKey(this);

		// Fetch the database ID of the movie to view
		if (Intent.ACTION_SEARCH.equals(getIntent().getAction())) {
			movieId = Integer.valueOf(getIntent().getStringExtra(SearchManager.EXTRA_DATA_KEY));
		} else {
			movieId = getIntent().getExtras().getInt("rowId");
		}

		awesomePager = (ViewPager) findViewById(R.id.awesomepager);
		awesomePager.setAdapter(new MovieDetailsAdapter(getSupportFragmentManager()));
		awesomePager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
			@Override
			public void onPageSelected(int position) {
				actionBar.setSelectedNavigationItem(position);
			}
		});

		if (savedInstanceState != null) {
			awesomePager.setCurrentItem(savedInstanceState.getInt("tab", 0));
		}

		// Set up database and open it
		db = MizuuApplication.getMovieAdapter();

		Cursor cursor = null;
		try {
			if (movieId > 0)
				cursor = db.fetchMovie(movieId);
			else
				cursor = db.fetchMovie(getIntent().getExtras().getString("tmdbId"));
			thisMovie = new Movie(this,
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_ROWID)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_FILEPATH)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_TITLE)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_PLOT)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_TAGLINE)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_TMDBID)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_IMDBID)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_RATING)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_RELEASEDATE)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_CERTIFICATION)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_RUNTIME)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_TRAILER)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_GENRES)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_FAVOURITE)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_CAST)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_COLLECTION)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_EXTRA_2)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_TO_WATCH)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_HAS_WATCHED)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_COVERPATH)),
					cursor.getString(cursor.getColumnIndex(DbAdapter.KEY_EXTRA_1)),
					ignorePrefixes,
					ignoreNfo
					);
		} catch (Exception e) {
			finish();
			return;
		} finally {
			cursor.close();
		}

		if (thisMovie != null) {
			// The the row ID again, if the MovieDetails activity was launched based on a TMDB ID
			movieId = Integer.parseInt(thisMovie.getRowId());

			if (db.hasMultipleVersions(thisMovie.getTmdbId())) {
				thisMovie.setMultipleVersions(db.getRowIdsForMovie(thisMovie.getTmdbId()));
			}

			setupSpinnerItems();
		} else {
			Toast.makeText(this, getString(R.string.errorSomethingWentWrong) + " (movie ID: " + movieId + ")", Toast.LENGTH_SHORT).show();
			finish();
			return;
		}
	}

	private void setupSpinnerItems() {
		spinnerItems.clear();
		spinnerItems.add(new SpinnerItem(thisMovie.getTitle(), getString(R.string.overview)));
		spinnerItems.add(new SpinnerItem(thisMovie.getTitle(), getString(R.string.detailsActors)));

		actionBar.setListNavigationCallbacks(spinnerAdapter, this);
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt("tab", awesomePager.getCurrentItem());
	}

	@SuppressLint("NewApi")
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.details, menu);

		try {
			if (thisMovie.isFavourite()) {
				menu.findItem(R.id.movie_fav).setIcon(R.drawable.fav);
				menu.findItem(R.id.movie_fav).setTitle(R.string.menuFavouriteTitleRemove);
			} else {
				menu.findItem(R.id.movie_fav).setIcon(R.drawable.reviews);
				menu.findItem(R.id.movie_fav).setTitle(R.string.menuFavouriteTitle);
			}

			if (thisMovie.toWatch()) {
				menu.findItem(R.id.watch_list).setIcon(R.drawable.watchlist_remove);
				menu.findItem(R.id.watch_list).setTitle(R.string.removeFromWatchlist);
			} else {
				menu.findItem(R.id.watch_list).setIcon(R.drawable.watchlist_add);
				menu.findItem(R.id.watch_list).setTitle(R.string.watchLater);
			}

			if (thisMovie.hasWatched()) {
				menu.findItem(R.id.watched).setTitle(R.string.stringMarkAsUnwatched);
			} else {
				menu.findItem(R.id.watched).setTitle(R.string.stringMarkAsWatched);
			}

			if (thisMovie.isNetworkFile() || thisMovie.isUpnpFile()) {
				menu.findItem(R.id.watchOffline).setVisible(true);				
				if (thisMovie.hasOfflineCopy())
					menu.findItem(R.id.watchOffline).setTitle(R.string.removeOfflineCopy);
				else
					menu.findItem(R.id.watchOffline).setTitle(R.string.watchOffline);
			}

			if (thisMovie.getTmdbId().isEmpty() || thisMovie.getTmdbId().equals("invalid"))
				menu.findItem(R.id.change_cover).setVisible(false);

		} catch (Exception e) {} // This can happen if thisMovie is null for whatever reason

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case android.R.id.home:
			if (getIntent().getExtras().getBoolean("isFromWidget")) {
				Intent i = new Intent(Intent.ACTION_VIEW);
				i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
				i.putExtra("startup", String.valueOf(Main.MOVIES));
				i.setClass(getApplicationContext(), Main.class);
				startActivity(i);
			}

			finish();
			return true;
		case R.id.share:
			Intent intent = new Intent(Intent.ACTION_SEND);
			intent.setType("text/plain");
			intent.putExtra(Intent.EXTRA_TEXT, "http://www.imdb.com/title/" + thisMovie.getImdbId());
			startActivity(intent);
			return true;
		case R.id.imdb:
			Intent imdbIntent = new Intent(Intent.ACTION_VIEW);
			imdbIntent.setData(Uri.parse("http://www.imdb.com/title/" + thisMovie.getImdbId()));
			startActivity(imdbIntent);
			return true;
		case R.id.tmdb:
			Intent tmdbIntent = new Intent(Intent.ACTION_VIEW);
			tmdbIntent.setData(Uri.parse("http://www.themoviedb.org/movie/" + thisMovie.getTmdbId()));
			startActivity(tmdbIntent);
			return true;
		case R.id.play_video:
			playMovie();
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

	public void deleteMovie(MenuItem item) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);

		View dialogLayout = getLayoutInflater().inflate(R.layout.delete_file_dialog_layout, null);
		final CheckBox cb = (CheckBox) dialogLayout.findViewById(R.id.deleteFile);
		if (thisMovie.isUpnpFile())
			cb.setEnabled(false);
		else
			cb.setChecked(PreferenceManager.getDefaultSharedPreferences(this).getBoolean(ALWAYS_DELETE_FILE, false));

		builder.setTitle(getString(R.string.removeMovie))
		.setView(dialogLayout)
		.setCancelable(false)
		.setPositiveButton(getString(android.R.string.yes), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {

				boolean deleted = true;
				if (thisMovie.hasMultipleVersions()) {
					MovieVersion[] versions = thisMovie.getMultipleVersions();
					for (int i = 0; i < versions.length; i++) {
						if (ignoreDeletedFiles)
							deleted = deleted && db.ignoreMovie(Long.valueOf(versions[i].getRowId()));
						else
							deleted = deleted && db.deleteMovie(Long.valueOf(versions[i].getRowId()));
					}
				} else {
					if (ignoreDeletedFiles)
						deleted = db.ignoreMovie(Long.valueOf(thisMovie.getRowId()));
					else
						deleted = db.deleteMovie(Long.valueOf(thisMovie.getRowId()));
				}

				if (deleted) {
					if (cb.isChecked()) {
						if (thisMovie.hasMultipleVersions()) {
							MovieVersion[] versions = thisMovie.getMultipleVersions();
							for (int i = 0; i < versions.length; i++) {
								Intent deleteIntent = new Intent(getApplicationContext(), DeleteFile.class);
								deleteIntent.putExtra("filepath", versions[i].getFilepath());
								getApplicationContext().startService(deleteIntent);
							}
						} else {						
							Intent deleteIntent = new Intent(getApplicationContext(), DeleteFile.class);
							deleteIntent.putExtra("filepath", thisMovie.getFilepath());
							getApplicationContext().startService(deleteIntent);
						}
					}

					boolean movieExists = db.movieExists(thisMovie.getTmdbId());

					// We only want to delete movie images, if there are no other versions of the same movie
					if (!movieExists) {
						try { // Delete cover art image
							File coverArt = thisMovie.getPoster();
							if (coverArt.exists() && coverArt.getAbsolutePath().contains("com.miz.mizuu")) {
								MizLib.deleteFile(coverArt);
							}
						} catch (NullPointerException e) {} // No file to delete

						try { // Delete thumbnail image
							File thumbnail = thisMovie.getThumbnail();
							if (thumbnail.exists() && thumbnail.getAbsolutePath().contains("com.miz.mizuu")) {
								MizLib.deleteFile(thumbnail);
							}
						} catch (NullPointerException e) {} // No file to delete

						try { // Delete backdrop image
							File backdrop = new File(thisMovie.getBackdrop());
							if (backdrop.exists() && backdrop.getAbsolutePath().contains("com.miz.mizuu")) {
								MizLib.deleteFile(backdrop);
							}
						} catch (NullPointerException e) {} // No file to delete
					}

					notifyDatasetChanges();
					finish();
					return;
				} else {
					Toast.makeText(getApplicationContext(), getString(R.string.failedToRemoveMovie), Toast.LENGTH_SHORT).show();
				}
			}
		})
		.setNegativeButton(getString(android.R.string.no), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				dialog.cancel();
			}
		})
		.create().show();
	}

	public void identifyMovie(MenuItem item) {
		if (thisMovie.hasMultipleVersions()) {
			final MovieVersion[] versions = thisMovie.getMultipleVersions();
			CharSequence[] items = new CharSequence[versions.length];
			for (int i = 0; i < versions.length; i++)
				items[i] = MizLib.transformSmbPath(versions[i].getFilepath());

			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setTitle(getString(R.string.fileToIdentify));
			builder.setItems(items, new OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					Intent intent = new Intent();

					String temp2 = versions[which].getFilepath();
					String temp = temp2.contains("<MiZ>") ? temp2.split("<MiZ>")[0] : temp2;

					intent.putExtra("fileName", temp);
					intent.putExtra("rowId", String.valueOf(versions[which].getRowId()));
					intent.setClass(MovieDetails.this, IdentifyMovie.class);
					startActivityForResult(intent, 0);
				};
			});
			builder.show();
		} else {
			Intent intent = new Intent();
			intent.putExtra("fileName", thisMovie.getManualIdentificationQuery());
			intent.putExtra("rowId", thisMovie.getRowId());
			intent.setClass(this, IdentifyMovie.class);
			startActivityForResult(intent, 0);
		}
	}

	public void shareMovie(MenuItem item) {
		Intent intent = new Intent(Intent.ACTION_SEND);
		intent.setType("text/plain");
		intent.putExtra(Intent.EXTRA_TEXT, "http://www.imdb.com/title/" + thisMovie.getImdbId());
		startActivity(Intent.createChooser(intent, getString(R.string.shareWith)));
	}

	public void favAction(MenuItem item) {
		thisMovie.setFavourite(!thisMovie.isFavourite()); // Reverse the favourite boolean

		boolean success = true;
		if (thisMovie.hasMultipleVersions()) {
			MovieVersion[] versions = thisMovie.getMultipleVersions();
			for (int i = 0; i < versions.length; i++)
				success = success && db.updateMovieSingleItem(Long.valueOf(versions[i].getRowId()), DbAdapter.KEY_FAVOURITE, thisMovie.getFavourite());
		} else {
			success = db.updateMovieSingleItem(Long.valueOf(thisMovie.getRowId()), DbAdapter.KEY_FAVOURITE, thisMovie.getFavourite());
		}

		if (success) {
			invalidateOptionsMenu();

			if (thisMovie.isFavourite()) {
				Toast.makeText(this, getString(R.string.addedToFavs), Toast.LENGTH_SHORT).show();
			} else {
				Toast.makeText(this, getString(R.string.removedFromFavs), Toast.LENGTH_SHORT).show();
				setResult(2); // Favorite removed
			}

			notifyDatasetChanges();

		} else Toast.makeText(this, getString(R.string.errorOccured), Toast.LENGTH_SHORT).show();

		new Thread() {
			@Override
			public void run() {
				ArrayList<Movie> movie = new ArrayList<Movie>();
				movie.add(thisMovie);
				MizLib.movieFavorite(movie, getApplicationContext());
			}
		}.start();
	}
	
	public void watched(MenuItem item) {
		watched(true);
	}

	private void watched(boolean showToast) {
		thisMovie.setHasWatched(!thisMovie.hasWatched()); // Reverse the hasWatched boolean

		boolean success = true;
		if (thisMovie.hasMultipleVersions()) {
			MovieVersion[] versions = thisMovie.getMultipleVersions();
			for (int i = 0; i < versions.length; i++)
				success = success && db.updateMovieSingleItem(Long.valueOf(versions[i].getRowId()), DbAdapter.KEY_HAS_WATCHED, thisMovie.getHasWatched());
		} else {
			success = db.updateMovieSingleItem(Long.valueOf(thisMovie.getRowId()), DbAdapter.KEY_HAS_WATCHED, thisMovie.getHasWatched());
		}

		if (success) {
			invalidateOptionsMenu();

			if (showToast)
				if (thisMovie.hasWatched()) {
					Toast.makeText(this, getString(R.string.markedAsWatched), Toast.LENGTH_SHORT).show();
				} else {
					Toast.makeText(this, getString(R.string.markedAsUnwatched), Toast.LENGTH_SHORT).show();
				}

			notifyDatasetChanges();

		} else Toast.makeText(this, getString(R.string.errorOccured), Toast.LENGTH_SHORT).show();

		if (prefsRemoveMoviesFromWatchlist)
			removeFromWatchlist();

		new Thread() {
			@Override
			public void run() {
				ArrayList<Movie> watchedMovies = new ArrayList<Movie>();
				watchedMovies.add(thisMovie);
				MizLib.markMovieAsWatched(watchedMovies, getApplicationContext());
			}
		}.start();
	}

	public void watchList(MenuItem item) {
		thisMovie.setToWatch(!thisMovie.toWatch()); // Reverse the toWatch boolean

		boolean success = true;
		if (thisMovie.hasMultipleVersions()) {
			MovieVersion[] versions = thisMovie.getMultipleVersions();
			for (int i = 0; i < versions.length; i++)
				success = success && db.updateMovieSingleItem(Long.valueOf(versions[i].getRowId()), DbAdapter.KEY_TO_WATCH, thisMovie.getToWatch());
		} else {
			success = db.updateMovieSingleItem(Long.valueOf(thisMovie.getRowId()), DbAdapter.KEY_TO_WATCH, thisMovie.getToWatch());
		}

		if (success) {
			invalidateOptionsMenu();

			if (thisMovie.toWatch()) {
				Toast.makeText(this, getString(R.string.addedToWatchList), Toast.LENGTH_SHORT).show();
			} else {
				Toast.makeText(this, getString(R.string.removedFromWatchList), Toast.LENGTH_SHORT).show();
			}

			notifyDatasetChanges();

		} else Toast.makeText(this, getString(R.string.errorOccured), Toast.LENGTH_SHORT).show();

		new Thread() {
			@Override
			public void run() {
				ArrayList<Movie> watchlist = new ArrayList<Movie>();
				watchlist.add(thisMovie);
				MizLib.movieWatchlist(watchlist, getApplicationContext());
			}
		}.start();
	}

	public void removeFromWatchlist() {
		thisMovie.setToWatch(false); // Remove it

		boolean success = true;
		if (thisMovie.hasMultipleVersions()) {
			MovieVersion[] versions = thisMovie.getMultipleVersions();
			for (int i = 0; i < versions.length; i++)
				success = success && db.updateMovieSingleItem(Long.valueOf(versions[i].getRowId()), DbAdapter.KEY_TO_WATCH, thisMovie.getToWatch());
		} else {
			success = db.updateMovieSingleItem(Long.valueOf(thisMovie.getRowId()), DbAdapter.KEY_TO_WATCH, thisMovie.getToWatch());
		}

		if (success) {
			invalidateOptionsMenu();
			notifyDatasetChanges();
		}

		new Thread() {
			@Override
			public void run() {
				ArrayList<Movie> watchlist = new ArrayList<Movie>();
				watchlist.add(thisMovie);
				MizLib.movieWatchlist(watchlist, getApplicationContext());
			}
		}.start();
	}

	public void watchTrailer(MenuItem item) {
		if (!MizLib.isEmpty(thisMovie.getLocalTrailer())) {
			try { // Attempt to launch intent based on the MIME type
				startActivity(MizLib.getVideoIntent(thisMovie.getLocalTrailer(), false, thisMovie.getTitle() + " " + getString(R.string.detailsTrailer)));
			} catch (Exception e) {
				try { // Attempt to launch intent based on wildcard MIME type
					startActivity(MizLib.getVideoIntent(thisMovie.getLocalTrailer(), "video/*", thisMovie.getTitle() + " " + getString(R.string.detailsTrailer)));
				} catch (Exception e2) {
					Toast.makeText(this, getString(R.string.noVideoPlayerFound), Toast.LENGTH_LONG).show();
				}
			}
		} else {
			if (!MizLib.isEmpty(thisMovie.getTrailer())) {
				if (YouTubeApiServiceUtil.isYouTubeApiServiceAvailable(getApplicationContext()).equals(YouTubeInitializationResult.SUCCESS)) {
					Intent intent = YouTubeStandalonePlayer.createVideoIntent(this, mYouTubeApiKey, MizLib.getYouTubeId(thisMovie.getTrailer()), 0, false, true);
					startActivity(intent);
				} else {
					Intent intent = new Intent(Intent.ACTION_VIEW);
					intent.setData(Uri.parse(thisMovie.getTrailer()));
					startActivity(intent);
				}
			} else {
				Toast.makeText(this, getString(R.string.searching), Toast.LENGTH_SHORT).show();
				new TmdbTrailerSearch().execute(thisMovie.getTmdbId());
			}
		}
	}

	public void watchOffline(MenuItem item) {
		if (thisMovie.hasOfflineCopy()) {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setMessage(getString(R.string.areYouSure))
			.setTitle(getString(R.string.removeOfflineCopy))
			.setCancelable(false)
			.setPositiveButton(getString(android.R.string.yes), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {	
					boolean success = thisMovie.getOfflineCopyFile().delete();
					if (!success)
						thisMovie.getOfflineCopyFile().delete();
					invalidateOptionsMenu();
					return;
				}
			})
			.setNegativeButton(getString(android.R.string.no), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					dialog.cancel();
				}
			})
			.create().show();
		} else {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setMessage(getString(R.string.downloadOfflineCopy))
			.setTitle(getString(R.string.watchOffline))
			.setCancelable(false)
			.setPositiveButton(getString(android.R.string.yes), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					if (MizLib.isLocalCopyBeingDownloaded(MovieDetails.this))
						Toast.makeText(getApplicationContext(), R.string.addedToDownloadQueue, Toast.LENGTH_SHORT).show();

					Intent i = new Intent(MovieDetails.this, MakeAvailableOffline.class);
					i.putExtra(MakeAvailableOffline.FILEPATH, thisMovie.getFilepath());
					i.putExtra(MakeAvailableOffline.TYPE, MizLib.TYPE_MOVIE);
					i.putExtra("thumb", thisMovie.getThumbnail());
					i.putExtra("backdrop", thisMovie.getBackdrop());
					startService(i);
					return;
				}
			})
			.setNegativeButton(getString(android.R.string.no), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					dialog.cancel();
				}
			})
			.create().show();
		}
	}

	private class TmdbTrailerSearch extends AsyncTask<String, Integer, String> {
		@Override
		protected String doInBackground(String... params) {
			try {
				JSONObject jObject = MizLib.getJSONObject("https://api.themoviedb.org/3/movie/" + params[0] + "/trailers?api_key=" + MizLib.getTmdbApiKey(getApplicationContext()));
				JSONArray trailers = jObject.getJSONArray("youtube");

				if (trailers.length() > 0)
					return trailers.getJSONObject(0).getString("source");
				else
					return null;
			} catch (Exception e) {
				return null;
			}
		}

		@Override
		protected void onPostExecute(String result) {
			if (result != null) {
				if (YouTubeApiServiceUtil.isYouTubeApiServiceAvailable(getApplicationContext()).equals(YouTubeInitializationResult.SUCCESS)) {
					Intent intent = YouTubeStandalonePlayer.createVideoIntent(MovieDetails.this, mYouTubeApiKey, MizLib.getYouTubeId(result), 0, false, true);
					startActivity(intent);
				} else {
					Intent intent = new Intent(Intent.ACTION_VIEW);
					intent.setData(Uri.parse(result));
				}
			} else {
				new YoutubeTrailerSearch().execute(thisMovie.getTitle());
			}
		}
	}

	private class YoutubeTrailerSearch extends AsyncTask<String, Integer, String> {
		@Override
		protected String doInBackground(String... params) {
			try {
				JSONObject jObject = MizLib.getJSONObject("https://gdata.youtube.com/feeds/api/videos?q=" + params[0] + "&max-results=20&alt=json&v=2");
				JSONObject jdata = jObject.getJSONObject("feed");
				JSONArray aitems = jdata.getJSONArray("entry");

				for (int i = 0; i < aitems.length(); i++) {
					JSONObject item0 = aitems.getJSONObject(i);

					// Check if the video can be embedded or viewed on a mobile device
					boolean embedding = false, mobile = false;
					JSONArray access = item0.getJSONArray("yt$accessControl");

					for (int j = 0; j < access.length(); j++) {
						if (access.getJSONObject(i).getString("action").equals("embed"))
							embedding = access.getJSONObject(i).getString("permission").equals("allowed") ? true : false;

						if (access.getJSONObject(i).getString("action").equals("syndicate"))
							mobile = access.getJSONObject(i).getString("permission").equals("allowed") ? true : false;
					}

					// Add the video ID if it's accessible from a mobile device
					if (embedding || mobile) {
						JSONObject id = item0.getJSONObject("id");
						String fullYTlink = id.getString("$t");
						return fullYTlink.substring(fullYTlink.lastIndexOf("video:") + 6);
					}
				}
			} catch (Exception e) {}

			return null;
		}

		@Override
		protected void onPostExecute(String result) {
			if (result != null) {
				if (YouTubeApiServiceUtil.isYouTubeApiServiceAvailable(getApplicationContext()).equals(YouTubeInitializationResult.SUCCESS)) {
					Intent intent = YouTubeStandalonePlayer.createVideoIntent(MovieDetails.this, mYouTubeApiKey, MizLib.getYouTubeId(result), 0, false, true);
					startActivity(intent);
				} else {
					Intent intent = new Intent(Intent.ACTION_VIEW);
					intent.setData(Uri.parse(result));
				}
			} else {
				Toast.makeText(getApplicationContext(), getString(R.string.errorSomethingWentWrong), Toast.LENGTH_LONG).show();
			}
		}
	}

	public void searchCover(MenuItem mi) {
		if (thisMovie.getTmdbId() != null && !thisMovie.getTmdbId().isEmpty() && MizLib.isOnline(getApplicationContext())) { // Make sure that the device is connected to the web and has the TMDb ID
			Intent intent = new Intent();
			intent.putExtra("tmdbId", thisMovie.getTmdbId());
			intent.putExtra("collectionId", thisMovie.getCollectionId());
			intent.setClass(this, MovieCoverFanartBrowser.class);
			startActivity(intent); // Start the intent for result
		} else {
			// No movie ID / Internet connection
			Toast.makeText(this, getString(R.string.coverSearchFailed), Toast.LENGTH_LONG).show();
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		if (resultCode == 1)
			updateWidgets();

		if (resultCode == 2 || resultCode == 4) {
			if (resultCode == 4) // The movie data has been edited
				Toast.makeText(this, getString(R.string.updatedMovie), Toast.LENGTH_SHORT).show();

			// Create a new Intent with the Bundle
			Intent intent = new Intent();
			intent.setClass(getApplicationContext(), MovieDetails.class);
			intent.putExtra("rowId", movieId);

			// Start the Intent for result
			startActivity(intent);

			finish();
			return;
		}
	}

	private void notifyDatasetChanges() {
		LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("mizuu-library-change"));
	}

	private void updateWidgets() {
		AppWidgetManager awm = AppWidgetManager.getInstance(this);
		awm.notifyAppWidgetViewDataChanged(awm.getAppWidgetIds(new ComponentName(this, MovieStackWidgetProvider.class)), R.id.stack_view); // Update stack view widget
		awm.notifyAppWidgetViewDataChanged(awm.getAppWidgetIds(new ComponentName(this, MovieCoverWidgetProvider.class)), R.id.widget_grid); // Update grid view widget
		awm.notifyAppWidgetViewDataChanged(awm.getAppWidgetIds(new ComponentName(this, MovieBackdropWidgetProvider.class)), R.id.widget_grid); // Update grid view widget
	}

	public void showEditMenu(MenuItem mi) {
		Intent intent = new Intent(this, EditMovie.class);
		intent.putExtra("rowId", Integer.valueOf(thisMovie.getRowId()));
		intent.putExtra("tmdbId", thisMovie.getTmdbId());
		startActivityForResult(intent, 1);
	}

	private class MovieDetailsAdapter extends FragmentPagerAdapter {

		public MovieDetailsAdapter(FragmentManager fm) {
			super(fm);
		}

		@Override  
		public Fragment getItem(int index) {
			switch (index) {
			case 0:
				return MovieDetailsFragment.newInstance(movieId);
			case 1:
				return ActorBrowserFragment.newInstance(thisMovie.getTmdbId());
			}
			return null;
		}  

		@Override  
		public int getCount() {  
			return 2;
		}
	}

	@Override
	public boolean onNavigationItemSelected(int itemPosition, long itemId) {
		awesomePager.setCurrentItem(itemPosition);
		return true;
	}

	@Override 
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		switch (keyCode) {
		case KeyEvent.KEYCODE_MEDIA_PLAY:
		case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
			playMovie();
		}
		return super.onKeyDown(keyCode, event);
	}

	private void checkIn() {
		new Thread() {
			@Override
			public void run() {
				MizLib.checkInMovieTrakt(thisMovie, getApplicationContext());
			}
		}.start();
	}

	private void playMovie() {
		if (thisMovie.hasOfflineCopy()) {
			playMovie(thisMovie.getOfflineCopyUri(), false);
		} else if (thisMovie.hasMultipleVersions() && !thisMovie.isUnidentified()) {
			final MovieVersion[] versions = thisMovie.getMultipleVersions();
			CharSequence[] items = new CharSequence[versions.length];
			for (int i = 0; i < versions.length; i++)
				items[i] = MizLib.transformSmbPath(versions[i].getFilepath());

			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setTitle(getString(R.string.fileToPlay));
			builder.setItems(items, new AlertDialog.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					playMovie(versions[which].getFilepath(), versions[which].getFilepath().contains("smb:/"));
				};
			});
			builder.show();
		} else {
			playMovie(thisMovie.getFilepath(), thisMovie.isNetworkFile());
		}
	}

	private void playMovie(String filepath, boolean isNetworkFile) {
		videoPlaybackStarted = System.currentTimeMillis();
		if (filepath.toLowerCase(Locale.getDefault()).matches(".*(cd1|part1).*")) {
			new GetSplitFiles(filepath, isNetworkFile).execute();
		} else {
			if (isNetworkFile) {
				playNetworkFile(filepath);
			} else {
				try { // Attempt to launch intent based on the MIME type
					startActivity(MizLib.getVideoIntent(filepath, useWildcard, thisMovie));
					checkIn();
				} catch (Exception e) {
					System.out.println(e);
					try { // Attempt to launch intent based on wildcard MIME type
						startActivity(MizLib.getVideoIntent(filepath, "video/*", thisMovie));
						checkIn();
					} catch (Exception e2) {
						System.out.println(e2);
						Toast.makeText(getApplicationContext(), getString(R.string.noVideoPlayerFound), Toast.LENGTH_LONG).show();
					}
				}
			}
		}
	}

	private class GetSplitFiles extends AsyncTask<String, Void, List<SplitFile>> {

		private ProgressDialog progress;
		private String orig_filepath;
		private boolean isNetworkFile;

		public GetSplitFiles(String filepath, boolean isNetworkFile) {
			this.orig_filepath = filepath;
			this.isNetworkFile = isNetworkFile;
		}

		@Override
		protected void onPreExecute() {
			progress = new ProgressDialog(getApplicationContext());
			progress.setIndeterminate(true);
			progress.setTitle(getString(R.string.loading_movie_parts));
			progress.setMessage(getString(R.string.few_moments));
			progress.show();
		}

		@Override
		protected List<SplitFile> doInBackground(String... params) {
			List<SplitFile> parts = new ArrayList<SplitFile>();
			List<String> temp;

			try {				
				if (isNetworkFile)
					temp = MizLib.getSplitParts(orig_filepath, MizLib.getAuthFromFilepath(MizLib.TYPE_MOVIE, orig_filepath));
				else
					temp = MizLib.getSplitParts(orig_filepath, null);

				for (int i = 0; i < temp.size(); i++)
					parts.add(new SplitFile(temp.get(i)));

			} catch (Exception e) {}

			return parts;
		}

		@Override
		protected void onPostExecute(final List<SplitFile> result) {
			progress.dismiss();
			if (result.size() > 1) {
				AlertDialog.Builder builder = new AlertDialog.Builder(getApplicationContext());
				builder.setTitle(getString(R.string.playPart));
				builder.setAdapter(new SplitAdapter(getApplicationContext(), result), new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						String filepath = result.get(which).getFilepath();

						if (isNetworkFile)
							playNetworkFile(filepath);
						else
							play(filepath);
					}});
				builder.show();
			} else if (result.size() == 1) {
				String filepath = result.get(0).getFilepath();

				if (isNetworkFile)
					playNetworkFile(filepath);
				else
					play(filepath);
			} else {
				Toast.makeText(getApplicationContext(), getString(R.string.errorSomethingWentWrong), Toast.LENGTH_LONG).show();
			}
		}
	}

	private class SplitAdapter implements android.widget.ListAdapter {

		private List<SplitFile> mFiles;
		private Context mContext;
		private LayoutInflater inflater;

		public SplitAdapter(Context context, List<SplitFile> files) {
			mContext = context;
			inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
			mFiles = files;
		}

		@Override
		public int getCount() {
			return mFiles.size();
		}

		@Override
		public Object getItem(int position) {
			return null;
		}

		@Override
		public long getItemId(int position) {
			return 0;
		}

		@Override
		public int getItemViewType(int position) {
			return 0;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {

			convertView = inflater.inflate(R.layout.split_file_item, parent, false);

			TextView title = (TextView) convertView.findViewById(R.id.title);
			TextView description = (TextView) convertView.findViewById(R.id.description);

			title.setText(getString(R.string.part) + " " + mFiles.get(position).getPartNumber());
			description.setText(mFiles.get(position).getUserFilepath());

			return convertView;
		}

		@Override
		public int getViewTypeCount() {
			return 1;
		}

		@Override
		public boolean hasStableIds() {
			return true;
		}

		@Override
		public boolean isEmpty() {
			return mFiles.isEmpty();
		}

		@Override
		public void registerDataSetObserver(DataSetObserver observer) {}

		@Override
		public void unregisterDataSetObserver(DataSetObserver observer) {}

		@Override
		public boolean areAllItemsEnabled() {
			return true;
		}

		@Override
		public boolean isEnabled(int position) {
			return true;
		}

	}

	private class SplitFile {

		String filepath;

		public SplitFile(String filepath) {
			this.filepath = filepath;
		}

		public String getFilepath() {
			return filepath;
		}

		public String getUserFilepath() {
			return MizLib.transformSmbPath(filepath);
		}

		public int getPartNumber() {
			return MizLib.getPartNumberFromFilepath(getUserFilepath());
		}

	}

	private void playNetworkFile(final String networkFilepath) {
		if (!MizLib.isWifiConnected(getApplicationContext(), prefsDisableEthernetWiFiCheck)) {
			Toast.makeText(getApplicationContext(), getString(R.string.noConnection), Toast.LENGTH_LONG).show();
			return;
		}

		int bufferSize;
		String buff = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString(BUFFER_SIZE, getString(R.string._16kb));
		if (buff.equals(getString(R.string._16kb)))
			bufferSize = 8192 * 2; // This appears to be the limit for most video players
		else bufferSize = 8192;

		final Streamer s = Streamer.getInstance();
		if (s != null)
			s.setBufferSize(bufferSize);
		else {
			Toast.makeText(getApplicationContext(), getString(R.string.errorOccured), Toast.LENGTH_SHORT).show();
			return;
		}

		final NtlmPasswordAuthentication auth = MizLib.getAuthFromFilepath(MizLib.TYPE_MOVIE, networkFilepath);

		new Thread(){
			public void run(){
				try{
					final SmbFile file = new SmbFile(
							MizLib.createSmbLoginString(
									URLEncoder.encode(auth.getDomain(), "utf-8"),
									URLEncoder.encode(auth.getUsername(), "utf-8"),
									URLEncoder.encode(auth.getPassword(), "utf-8"),
									networkFilepath,
									false
									));

					if (networkFilepath.endsWith("VIDEO_TS.IFO"))
						s.setStreamSrc(file, MizLib.getDVDFiles(networkFilepath, auth));
					else
						s.setStreamSrc(file, MizLib.getSubtitleFiles(networkFilepath, auth));

					runOnUiThread(new Runnable(){
						public void run(){
							try{
								Uri uri = Uri.parse(Streamer.URL + Uri.fromFile(new File(Uri.parse(networkFilepath).getPath())).getEncodedPath());	
								mContext.startActivity(MizLib.getVideoIntent(uri, useWildcard, thisMovie));
								checkIn();
							} catch (Exception e) {
								try { // Attempt to launch intent based on wildcard MIME type
									Uri uri = Uri.parse(Streamer.URL + Uri.fromFile(new File(Uri.parse(networkFilepath).getPath())).getEncodedPath());	
									mContext.startActivity(MizLib.getVideoIntent(uri, "video/*", thisMovie));
									checkIn();
								} catch (Exception e2) {
									Toast.makeText(getApplicationContext(), getString(R.string.noVideoPlayerFound), Toast.LENGTH_LONG).show();
								}
							}
						}
					});
				}
				catch (MalformedURLException e) {}
				catch (UnsupportedEncodingException e1) {}
			}
		}.start();
	}

	private void play(String filepath) {
		try { // Attempt to launch intent based on the MIME type
			getApplicationContext().startActivity(MizLib.getVideoIntent(filepath, useWildcard, thisMovie));
			checkIn();
		} catch (Exception e) {
			try { // Attempt to launch intent based on wildcard MIME type
				getApplicationContext().startActivity(MizLib.getVideoIntent(filepath, "video/*", thisMovie));
				checkIn();
			} catch (Exception e2) {
				Toast.makeText(getApplicationContext(), getString(R.string.noVideoPlayerFound), Toast.LENGTH_LONG).show();
			}
		}
	}

	public void onResume() {
		super.onResume();

		videoPlaybackEnded = System.currentTimeMillis();

		if (videoPlaybackStarted > 0 && videoPlaybackEnded - videoPlaybackStarted > (1000 * 60 * 5)) {
			if (!thisMovie.hasWatched())
				watched(false); // Mark it as watched
		}
	}
}