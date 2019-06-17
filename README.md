# GifImageView

Android ImageView that handles Animated GIF images

[ ![Download](https://api.bintray.com/packages/weixia/maven/X-gifimageview/images/download.svg) ](https://bintray.com/weixia/maven/X-gifimageview/_latestVersion)

### Usage

In your ``build.gradle`` file:

```groovy
dependencies {
  compile 'me.xia:X-gifimageview:1.0.0'
}
```

In your `Activity` class:

```java
@Override protected void onCreate(final Bundle savedInstanceState) {
  super.onCreate(savedInstanceState);
  gifView = findViewById(R.id.gifImageView);
  gifView.setBytes(bitmapData);
  //or
  //gifView.setBytes(bitmapData,true);//is only load once
}

@Override protected void onStart() {
  super.onStart();
  gifView.startAnimation();
}

@Override protected void onStop() {
  super.onStop();
  gifView.stopAnimation();
}
```

If you need to post-process the GIF frames, you can do that via ``GifImageView.setOnFrameAvailable()``.
You can see an example of that in the sample app included on the repository.

```java
gifImageView.setOnFrameAvailable(new GifImageView.OnFrameAvailable() {
  @Override public Bitmap onFrameAvailable(Bitmap bitmap) {
    return blurFilter.blur(bitmap);
  }
});
```

You can also reset an animation to play again from the beginning `gifImageView.resetAnimation();` or show a specific frame of the animation `gifImageView.gotoFrame(3)`;

### Demo

![](https://raw.githubusercontent.com/felipecsl/GifImageView/master/demo.gif)

Be sure to also check the [demo project](https://github.com/felipecsl/GifImageView/blob/master/app/src/main/java/com/felipecsl/gifimageview/app/MainActivity.java) for a sample of usage!

Snapshots of the development version are available in [Sonatype's `snapshots` repository](https://oss.sonatype.org/content/repositories/snapshots/).

### Contributing

* Check out the latest master to make sure the feature hasn't been implemented or the bug hasn't been fixed yet
* Check out the issue tracker to make sure someone already hasn't requested it and/or contributed it
* Fork the project
* Start a feature/bugfix branch
* Commit and push until you are happy with your contribution
* Make sure to add tests for it. This is important so I don't break it in a future version unintentionally.

### Copyright and license

Code and documentation copyright 2011- Felipe Lima.
Code released under the [MIT license](https://github.com/felipecsl/GifImageView/blob/master/LICENSE.txt).
