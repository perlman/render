package org.janelia.alignment.transform;

import mpicbg.trakem2.transform.CoordinateTransform;

import net.imglib2.RandomAccessible;
import net.imglib2.RealRandomAccess;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.composite.RealComposite;

/**
 * Transform that utilizes an {@link AffineWarpField}.
 *
 * @author Eric Trautman
 */
public class AffineWarpFieldTransform
        implements CoordinateTransform {

    public static final double[] EMPTY_OFFSETS = new double[] {0.0, 0.0};

    private double[] locationOffsets;
    private AffineWarpField affineWarpField;

    // ImgLib2 accessor for warp field
    private RealRandomAccess<RealComposite<DoubleType>> warpFieldAccessor;

    /**
     * This constructor applies identity transform to entire space with no offset.
     */
    public AffineWarpFieldTransform() {
        this(EMPTY_OFFSETS, new AffineWarpField());
    }

    /**
     * This constructor applies the specified warp field with the specified offset.
     *
     * @param  affineWarpField  warp field.
     *
     * @param  locationOffsets  x and y offsets to apply when transforming coordinates.
     *                          Offsets are subtracted from locations before applying the warp field
     *                          and then are added back after the warp field has been applied.
     *
     * @throws IllegalArgumentException
     *   if the specified warp field references an invalid interpolator.
     */
    public AffineWarpFieldTransform(final double[] locationOffsets,
                                    final AffineWarpField affineWarpField)
            throws IllegalArgumentException {

        this.locationOffsets = locationOffsets;
        this.affineWarpField = affineWarpField;
        setWarpFieldAccessor();
    }

    @Override
    public double[] apply(final double[] location) {
        final double[] out = location.clone();
        applyInPlace(out);
        return out;
    }

    @Override
    public void applyInPlace(final double[] location) {

        // subtract offsets before applying warp field
        location[0] = location[0] - locationOffsets[0];
        location[1] = location[1] - locationOffsets[1];

        warpFieldAccessor.setPosition(location);
        final RealComposite<DoubleType> coefficients = warpFieldAccessor.get();

        final double m00 = coefficients.get(0).getRealDouble();
        final double m10 = coefficients.get(1).getRealDouble();
        final double m01 = coefficients.get(2).getRealDouble();
        final double m11 = coefficients.get(3).getRealDouble();
        final double m02 = coefficients.get(4).getRealDouble();
        final double m12 = coefficients.get(5).getRealDouble();

        // stolen from AffineModel2D.applyInPlace
        final double l0 = location[0];
        location[0] = l0 * m00 + location[1] * m01 + m02;
        location[1] = l0 * m10 + location[1] * m11 + m12;

        // restore (add) offsets back once warp field has been applied
        location[0] = location[0] + locationOffsets[0];
        location[1] = location[1] + locationOffsets[1];
    }

    /**
     * Initializes this transform by de-serializing a warp field instance from the specified data string.
     *
     * Note that before using instance, interpolator factory must be validated by
     * calling {@link AffineWarpField#getAccessor}.
     *
     * @param  data  string serialization of a warp field.
     *
     * @throws IllegalArgumentException
     *   if any errors occur during parsing.
     */
    @Override
    public void init(final String data) throws IllegalArgumentException {

        final String[] fields = data.split("\\s+");

        final int valuesStartIndex = 8;

        if (fields.length > valuesStartIndex) {

            this.locationOffsets = new double[] {
                    Double.parseDouble(fields[0]),
                    Double.parseDouble(fields[1])
            };

            final double width = Double.parseDouble(fields[2]);
            final double height = Double.parseDouble(fields[3]);
            final int rowCount = Integer.parseInt(fields[4]);
            final int columnCount = Integer.parseInt(fields[5]);
            final InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>>
                    interpolatorFactory = buildInterpolatorFactoryInstance(fields[6]);
            final String encoding = fields[7];

            final int size = AffineWarpField.getSize(rowCount, columnCount);
            final double[] values;

            if (BASE_64_ENCODING.equals(encoding)) {

                try {
                    values = DoubleArrayConverter.decodeBase64(fields[valuesStartIndex], size);
                } catch (final Exception e) {
                    throw new IllegalArgumentException("failed to decode warp field values", e);
                }

            } else {

                final int expectedSize = size + valuesStartIndex;

                if (fields.length == expectedSize) {

                    values = new double[size];

                    for (int i = valuesStartIndex; i < fields.length; i++) {
                        values[i - valuesStartIndex] = Double.parseDouble(fields[i]);
                    }

                } else {
                    throw new IllegalArgumentException("expected warp field data to contain " + expectedSize +
                                                       " fields but found " + fields.length + " instead");
                }

            }

            this.affineWarpField = new AffineWarpField(width, height, rowCount, columnCount, values, interpolatorFactory);

        } else {
            throw new IllegalArgumentException("warp field data must contain at least " + valuesStartIndex + " fields");
        }

        setWarpFieldAccessor();
    }

    @Override
    public String toXML(final String indent) {
        final StringBuilder xml = new StringBuilder();
        xml.append(indent).append("<ict_transform class=\"")
                .append(this.getClass().getCanonicalName())
                .append("\" data=\"");
        serializeWarpField(xml);
        return xml.append("\"/>").toString();
    }

    @Override
    public String toDataString() {
        final StringBuilder data = new StringBuilder();
        serializeWarpField(data);
        return data.toString();
    }

    @Override
    public CoordinateTransform copy() {
        return new AffineWarpFieldTransform(locationOffsets.clone(),
                                            affineWarpField.getCopy());
    }

    private void setWarpFieldAccessor() throws IllegalArgumentException {
        // set accessor and validate interpolator factory instance
        try {
            warpFieldAccessor = affineWarpField.getAccessor();
        } catch (final Exception e) {
            final String factoryClassName = affineWarpField.getInterpolatorFactory().getClass().getCanonicalName();
            throw new IllegalArgumentException("interpolator factory class '" + factoryClassName + "' does not implement required interface", e);
        }
    }

    /**
     * Appends serialization of this transform's offsets and warp field to the specified data string.
     *
     * @param  data             target data string.
     */
    private void serializeWarpField(final StringBuilder data) {
        data.append(locationOffsets[0]).append(' ').append(locationOffsets[1]).append(' ');
        data.append(affineWarpField.getWidth()).append(' ').append(affineWarpField.getHeight()).append(' ');
        data.append(affineWarpField.getRowCount()).append(' ').append(affineWarpField.getColumnCount()).append(' ');
        final InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>> factory =
                affineWarpField.getInterpolatorFactory();
        data.append(factory.getClass().getCanonicalName()).append(' ');
        final double[] values = affineWarpField.getValues();
        if (values.length < 64) { // skip encoding for smaller fields to simplify visual inspection and testing
            data.append(NO_ENCODING);
            for (final double value : values) {
                data.append(' ').append(value);
            }
        } else {
            data.append(BASE_64_ENCODING).append(' ').append(DoubleArrayConverter.encodeBase64(values));
        }
    }

    /**
     * @return a factory instance for the specified class name.
     *         Note that instance may not completely implement interface because type erasure prevents this check.
     *
     * @throws IllegalArgumentException
     *   if an instance cannot be created.
     */
    private static InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>> buildInterpolatorFactoryInstance(final String className)
            throws IllegalArgumentException {

        final Class clazz;
        try {
            clazz = Class.forName(className);
        } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException("class '" + className + "' cannot be found", e);
        }

        final Object instance;
        try {
            instance = clazz.newInstance();
        } catch (final Exception e) {
            throw new IllegalArgumentException("failed to create instance of '" + className + "'", e);
        }

        final InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>> factory;
        try {
            //noinspection unchecked
            factory = (InterpolatorFactory<RealComposite<DoubleType>, RandomAccessible<RealComposite<DoubleType>>>) instance;
        } catch (final Exception e) {
            throw new IllegalArgumentException("class '" + className + "' does not implement required interface", e);
        }

        return factory;
    }

    private static final String BASE_64_ENCODING = "base64";
    private static final String NO_ENCODING = "none";

}
