package com.sensifai.enhancement.TFLite;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.sensifai.enhancement.Device;
import com.sensifai.enhancement.results.ProcessResult;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.HexagonDelegate;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

//import org.tensorflow.lite.flex.FlexDelegate;

public abstract class Processor<E> implements com.sensifai.enhancement.Processor<E> {
    private static final String TAG = Processor.class.getSimpleName();

    private final Map<String, ModelInfo<E>> modelsMap;
    protected String[] labels;
    protected ModelInfo<E> model;
    protected Context context;


    // TFLite specific variables
    protected Interpreter tflite;
    //    private FlexDelegate flexDelegate = null; /** Optional Flex delegate for TF ops support. */
    protected TensorBuffer[] tfliteOutputs;
    /**
     * Optional GPU delegate for accleration.
     */
    private GpuDelegate gpuDelegate = null;
    /**
     * Optional NNAPI delegate for accleration.
     */
    private NnApiDelegate nnApiDelegate = null;
    /**
     * Optional Hexagon delegate for accleration.
     */
    private HexagonDelegate dspDelegate = null;

    Processor(Map<String, ModelInfo<E>> modelsMap) {
        this.modelsMap = modelsMap;
    }

    /**
     * initialize all requirement object and load model file based on @modelName argument
     * @param context to get application context
     * @param modelName The name of the model we intend to use
     * @param device Which hardware to use to perform the process
     * @param numThreads The number of threads to be used for the process
     * @return return true if everything ok else return false
     */

    public boolean init(Context context, String modelName, Device device, int numThreads) {
        this.context = context;
        Application application = ((Application) context.getApplicationContext());
        tflite = null;
        model = modelsMap.get(modelName);
        if (model == null) {
            Log.e(TAG, String.format("No model exists named %s.", modelName));
            return false;
        }

        try {
            Interpreter.Options tfliteOptions = new Interpreter.Options();
            // flexDelegate = new FlexDelegate();
            // tfliteOptions.addDelegate(flexDelegate);
            switch (device) {
                case NNAPI:
                    nnApiDelegate = new NnApiDelegate();
                    tfliteOptions.addDelegate(nnApiDelegate);
                    break;
                case GPU:
                    gpuDelegate = new GpuDelegate();
                    tfliteOptions.addDelegate(gpuDelegate);
                    break;
                case DSP:
                    dspDelegate = new HexagonDelegate(application);
                    tfliteOptions.addDelegate(dspDelegate);
                    break;
                case CPU:
                    break;
            }
            MappedByteBuffer tfliteModel = FileUtil.loadMappedFile(application, model.getModelFileName());
            tfliteOptions.setNumThreads(numThreads);
            tflite = new Interpreter(tfliteModel, tfliteOptions);

            List<TensorBuffer> outputsList = new ArrayList<>(tflite.getOutputTensorCount());
            for (int i = 0; i < tflite.getOutputTensorCount(); i++) {
                int[] outputShape = tflite.getOutputTensor(i).shape();
                DataType outputDataType = tflite.getOutputTensor(i).dataType();
                outputsList.add(TensorBuffer.createFixedSize(outputShape, outputDataType));
            }
            tfliteOutputs = outputsList.toArray(new TensorBuffer[0]);
            Log.i(TAG, "Network loaded successfully.");
            if (model.getLabelFileName() != null) {
                List<String> labelsList = FileUtil.loadLabels(application, model.getLabelFileName());
                labels = labelsList.toArray(new String[0]);
            }
            return true;
        } catch (Exception ex) {
            release();
            Log.e(TAG, ex.getMessage());
        }
        return false;
    }

    public boolean release() {
        // TFLite release
        if (tflite != null) {
            tflite.close();
            tflite = null;
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        if (nnApiDelegate != null) {
            nnApiDelegate.close();
            nnApiDelegate = null;
        }
        if (dspDelegate != null) {
            dspDelegate.close();
            dspDelegate = null;
        }
//        if (flexDelegate != null) {
//            flexDelegate.close();
//            flexDelegate = null;
//        }
        tfliteOutputs = null;
        return true;
    }

    public Context getContext() {
        return context;
    }

    @Override
    public ProcessResult<E> process(Bitmap[] images, int orientation) {
        if (model.getModelType() == ModelInfo.ModelType.TFLite && tflite == null) {
            Log.e(TAG, "Not initialized.");
            return null;
        }

        try {
            long totalTime = 0;
            long inferenceTime = 0;
            long preProcessTime = 0;
            long postProcessTime = 0;
            long inferenceEndTime = 0;
            TensorBuffer[] outputs = null;
            List<E> allResults = new ArrayList<>();

            long totalStartTime = SystemClock.uptimeMillis();
            for (Bitmap image : images) {
                // Pre-processing
                TensorBuffer input = model.getPreProcessor().apply(image, orientation);
                preProcessTime = SystemClock.uptimeMillis() - totalStartTime;

                // Runtime.getRuntime().gc();

                // Inferencing
                Object[] inputArray = {input.getBuffer()};
                Map<Integer, Object> outputMap = new HashMap<>();
                int i = 0;
                for (TensorBuffer output : tfliteOutputs) {
                    outputMap.put(i++, output.getBuffer().rewind());
                }

                long inferenceStartTime = SystemClock.uptimeMillis();
                tflite.runForMultipleInputsOutputs(inputArray, outputMap);
                inferenceEndTime = SystemClock.uptimeMillis();
                inferenceTime = inferenceEndTime - inferenceStartTime;

                outputs = tfliteOutputs;

                // Post-processing
                E[] results = model.getPostProcessor()
                        .apply(outputs, image.getWidth(), image.getHeight(), orientation, input);
                allResults.addAll(Arrays.asList(results));
            }
            postProcessTime = SystemClock.uptimeMillis() - inferenceEndTime;
            totalTime = SystemClock.uptimeMillis() - totalStartTime;
            Log.i(TAG, String.format("Total time %d ms.", totalTime));
            Log.i(TAG, String.format("preProcess time %d ms.", preProcessTime));
            Log.i(TAG, String.format("postProcess time %d ms.", postProcessTime));
            Log.i(TAG, String.format("Inference time %d ms.", inferenceTime));

            return new ProcessResult<>(allResults, labels, inferenceTime, totalTime);

        } catch (Exception ex) {
//            Log.e(TAG, ex.getMessage());
            ex.printStackTrace();
        }
        return null;
    }

    public Set<String> getAllModels() {
        return modelsMap.keySet();
    }
}
