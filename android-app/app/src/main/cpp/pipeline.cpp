#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <net.h>
#include <gpu.h>
#include <cpu.h>
#include <chrono>
#include <algorithm>
#include <cmath>
#include <locale>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <vector>
#include "model_labels.h"

namespace {
struct Box { float x1,y1,x2,y2,score; int cls; };
struct Engine {
    std::unique_ptr<ncnn::Net> locator, reader;
    AAssetManager* assets=nullptr;
    bool gpu=false;
};
using Clock=std::chrono::steady_clock;
long milliseconds(Clock::time_point start){return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now()-start).count();}
float iou(const Box& a,const Box& b) {
    float area=std::max(0.f,std::min(a.x2,b.x2)-std::max(a.x1,b.x1))*std::max(0.f,std::min(a.y2,b.y2)-std::max(a.y1,b.y1));
    float total=(a.x2-a.x1)*(a.y2-a.y1)+(b.x2-b.x1)*(b.y2-b.y1)-area;
    return total>0?area/total:0;
}
std::vector<Box> suppress(std::vector<Box> boxes,float threshold,bool classAware) {
    std::stable_sort(boxes.begin(),boxes.end(),[](auto& a,auto& b){return a.score>b.score;});
    std::vector<Box> kept;
    for(auto& box:boxes) {
        bool overlap=false;
        for(auto& other:kept) if((!classAware||box.cls==other.cls)&&iou(box,other)>threshold){overlap=true;break;}
        if(!overlap) kept.push_back(box);
        if(kept.size()==300) break;
    }
    return kept;
}
std::vector<Box> infer(ncnn::Net& model,const ncnn::Mat& rgb,int classes,float confidence) {
    float scale=std::min(640.f/rgb.w,640.f/rgb.h);
    int rw=std::max(1,(int)std::round(rgb.w*scale)),rh=std::max(1,(int)std::round(rgb.h*scale));
    int left=(640-rw)/2,top=(640-rh)/2;
    ncnn::Mat resized,input,output;
    ncnn::resize_bilinear(rgb,resized,rw,rh);
    ncnn::copy_make_border(resized,input,top,640-rh-top,left,640-rw-left,ncnn::BORDER_CONSTANT,114.f);
    const float norm[]={1.f/255,1.f/255,1.f/255};input.substract_mean_normalize(nullptr,norm);
    auto extractor=model.create_extractor();
    if(extractor.input("in0",input)!=0||extractor.extract("out0",output)!=0) throw std::runtime_error("Inference failed");
    if(output.dims!=2||output.h!=4+classes) throw std::runtime_error("Unexpected output shape");
    std::vector<Box> boxes;
    for(int j=0;j<output.w;j++) {
        int cls=0;float score=0;
        for(int c=0;c<classes;c++) {float value=output.row(4+c)[j];if(value>score){score=value;cls=c;}}
        if(score<confidence) continue;
        float cx=output.row(0)[j],cy=output.row(1)[j],w=output.row(2)[j],h=output.row(3)[j];
        if(!std::isfinite(cx+cy+w+h+score)||w<=0||h<=0) continue;
        Box b{(cx-w/2-left)/scale,(cy-h/2-top)/scale,(cx+w/2-left)/scale,(cy+h/2-top)/scale,score,cls};
        if(b.x2>b.x1&&b.y2>b.y1) boxes.push_back(b);
    }
    auto kept=suppress(boxes,.7f,true);
    for(auto& b:kept){b.x1=std::clamp(b.x1,0.f,(float)rgb.w);b.x2=std::clamp(b.x2,0.f,(float)rgb.w);b.y1=std::clamp(b.y1,0.f,(float)rgb.h);b.y2=std::clamp(b.y2,0.f,(float)rgb.h);}
    kept.erase(std::remove_if(kept.begin(),kept.end(),[](auto& b){return b.x2<=b.x1||b.y2<=b.y1;}),kept.end());
    return kept;
}
void throwJava(JNIEnv* env,const char* message) {env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),message);}
std::unique_ptr<ncnn::Net> load(AAssetManager* assets,const char* folder,bool gpu) {
    auto net=std::make_unique<ncnn::Net>();
    net->opt.num_threads=std::max(1,std::min(4,ncnn::get_big_cpu_count()));
    net->opt.use_vulkan_compute=gpu;
    net->opt.use_fp16_arithmetic=false;
    std::string prefix=std::string("models/")+folder+"/model.ncnn";
    if(net->load_param(assets,(prefix+".param").c_str())!=0||net->load_model(assets,(prefix+".bin").c_str())!=0)
        throw std::runtime_error("Unable to load bundled models");
    return net;
}
}

void loadModels(Engine& engine,bool gpu) {
    auto locator=load(engine.assets,"plate_detector",gpu),reader=load(engine.assets,"characters",gpu);
    engine.locator=std::move(locator);engine.reader=std::move(reader);engine.gpu=gpu;
}
std::string encode(const std::vector<Box>& plates,const std::vector<std::vector<Box>>& readings,long detectorMs,long readerMs,bool gpu) {
    std::ostringstream json;json.imbue(std::locale::classic());
    json<<"{\"backend\":\""<<(gpu?"GPU (Vulkan)":"CPU")<<"\",\"detector_ms\":"<<detectorMs<<",\"reader_ms\":"<<readerMs<<",\"completed\":"<<readings.size()<<",\"plates\":[";
    for(size_t p=0;p<plates.size();p++) {
        const auto& plate=plates[p];if(p)json<<',';
        json<<"{\"box\":["<<plate.x1<<','<<plate.y1<<','<<plate.x2<<','<<plate.y2<<"],\"confidence\":"<<plate.score<<",\"text\":\"";
        if(p<readings.size())for(auto& ch:readings[p])json<<CHARACTER_TOKENS[ch.cls];
        json<<"\",\"tokens\":[";
        if(p<readings.size())for(size_t i=0;i<readings[p].size();i++){if(i)json<<',';json<<'\"'<<CHARACTER_TOKENS[readings[p][i].cls]<<'\"';}
        json<<"]}";
    }
    json<<"]}";return json.str();
}
extern "C" JNIEXPORT jlong JNICALL Java_ir_platelens_app_NativeEngine_nativeLoad(JNIEnv* env,jclass,jobject manager,jboolean preferGpu) {
    try {
        static std::once_flag initialized;
        std::call_once(initialized,[]{ncnn::create_gpu_instance();});
        auto engine=std::make_unique<Engine>();engine->assets=AAssetManager_fromJava(env,manager);
        bool gpu=preferGpu&&ncnn::get_gpu_count()>0;
        try {loadModels(*engine,gpu);} catch(const std::exception&){if(!gpu)throw;loadModels(*engine,false);}
        return reinterpret_cast<jlong>(engine.release());
    } catch(const std::exception& ex){throwJava(env,ex.what());return 0;}
}
extern "C" JNIEXPORT jstring JNICALL Java_ir_platelens_app_NativeEngine_nativeBackend(JNIEnv* env,jclass,jlong handle) {
    auto* engine=reinterpret_cast<Engine*>(handle);
    return env->NewStringUTF(engine&&engine->gpu?"GPU (Vulkan)":"CPU");
}
extern "C" JNIEXPORT jstring JNICALL Java_ir_platelens_app_NativeEngine_nativeDetect(JNIEnv* env,jclass,jlong handle,jobject bitmap,jobject progress) {
    try {
        auto* engine=reinterpret_cast<Engine*>(handle);
        if(!engine) throw std::runtime_error("Models are not ready");
        AndroidBitmapInfo info{};void* pixels=nullptr;
        if(AndroidBitmap_getInfo(env,bitmap,&info)!=0||info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888) throw std::runtime_error("Expected ARGB_8888 bitmap");
        if(AndroidBitmap_lockPixels(env,bitmap,&pixels)!=0) throw std::runtime_error("Cannot read bitmap");
        ncnn::Mat rgb=ncnn::Mat::from_pixels((unsigned char*)pixels,ncnn::Mat::PIXEL_RGBA2RGB,info.width,info.height,info.stride);
        AndroidBitmap_unlockPixels(env,bitmap);
        for(int attempt=0;attempt<2;attempt++) {
            try {
                auto start=Clock::now();auto plates=infer(*engine->locator,rgb,1,.5f);long detectorMs=milliseconds(start);
                // Keep full input resolution and all five reads, but publish completed plates incrementally.
                if(plates.size()>5)plates.resize(5);
                std::vector<std::vector<Box>> readings;long readerMs=0;
                jmethodID callback=progress?env->GetMethodID(env->GetObjectClass(progress),"onProgress","(Ljava/lang/String;)V"):nullptr;
                if(env->ExceptionCheck())return nullptr;
                for(auto& plate:plates) {
                    start=Clock::now();
                    int x1=std::max(0,(int)std::floor(plate.x1)-5),y1=std::max(0,(int)std::floor(plate.y1)-5);
                    int x2=std::min(rgb.w,(int)std::ceil(plate.x2)+5),y2=std::min(rgb.h,(int)std::ceil(plate.y2)+5);
                    ncnn::Mat crop;ncnn::copy_cut_border(rgb,crop,y1,rgb.h-y2,x1,rgb.w-x2);
                    auto chars=suppress(infer(*engine->reader,crop,37,.25f),.5f,false);
                    std::stable_sort(chars.begin(),chars.end(),[](auto& a,auto& b){return a.x1+a.x2<b.x1+b.x2;});
                    readings.push_back(std::move(chars));readerMs+=milliseconds(start);
                    if(callback&&readings.size()<plates.size()) {
                        jstring value=env->NewStringUTF(encode(plates,readings,detectorMs,readerMs,engine->gpu).c_str());
                        env->CallVoidMethod(progress,callback,value);env->DeleteLocalRef(value);
                        if(env->ExceptionCheck())return nullptr;
                    }
                }
                return env->NewStringUTF(encode(plates,readings,detectorMs,readerMs,engine->gpu).c_str());
            } catch(const std::exception&) {
                if(!engine->gpu||attempt!=0)throw;
                loadModels(*engine,false);
            }
        }
        throw std::runtime_error("Inference failed after CPU fallback");
    } catch(const std::exception& ex){throwJava(env,ex.what());return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_ir_platelens_app_NativeEngine_nativeClose(JNIEnv*,jclass,jlong handle) {
    delete reinterpret_cast<Engine*>(handle);
}
