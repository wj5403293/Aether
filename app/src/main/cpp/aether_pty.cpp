#include <jni.h>
#include <pty.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

std::string jstring_to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<std::string> jstring_array_to_vector(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> result;
    if (array == nullptr) return result;
    const jsize size = env->GetArrayLength(array);
    result.reserve(static_cast<size_t>(size));
    for (jsize i = 0; i < size; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        result.push_back(jstring_to_string(env, item));
        env->DeleteLocalRef(item);
    }
    return result;
}

jintArray make_result(JNIEnv* env, jint master_fd, jint pid, jint error_code) {
    jint values[3] = {master_fd, pid, error_code};
    jintArray result = env->NewIntArray(3);
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, 3, values);
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_zhousl_aether_runtime_AetherPty_nativeStart(
    JNIEnv* env,
    jobject,
    jobjectArray command_array,
    jobjectArray environment_array,
    jstring working_directory,
    jint rows,
    jint columns
) {
    auto command = jstring_array_to_vector(env, command_array);
    if (command.empty()) {
        return make_result(env, -1, -1, EINVAL);
    }

    std::vector<char*> argv;
    argv.reserve(command.size() + 1);
    for (auto& part : command) {
        argv.push_back(part.data());
    }
    argv.push_back(nullptr);

    auto environment = jstring_array_to_vector(env, environment_array);
    std::vector<char*> envp;
    envp.reserve(environment.size() + 1);
    for (auto& part : environment) {
        envp.push_back(part.data());
    }
    envp.push_back(nullptr);

    const std::string cwd = jstring_to_string(env, working_directory);
    winsize window_size{};
    window_size.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    window_size.ws_col = static_cast<unsigned short>(columns > 0 ? columns : 80);

    int master_fd = -1;
    pid_t pid = forkpty(&master_fd, nullptr, nullptr, &window_size);
    if (pid < 0) {
        return make_result(env, -1, -1, errno);
    }

    if (pid == 0) {
        if (!cwd.empty()) {
            chdir(cwd.c_str());
        }
        execve(argv[0], argv.data(), envp.data());
        _exit(127);
    }

    return make_result(env, master_fd, static_cast<jint>(pid), 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zhousl_aether_runtime_AetherPty_nativeRead(
    JNIEnv* env,
    jobject,
    jint fd,
    jbyteArray buffer,
    jint offset,
    jint length
) {
    if (fd < 0 || buffer == nullptr || offset < 0 || length <= 0) return -EINVAL;
    const jsize buffer_size = env->GetArrayLength(buffer);
    if (offset + length > buffer_size) return -EINVAL;

    std::vector<jbyte> local(static_cast<size_t>(length));
    ssize_t count;
    do {
        count = read(fd, local.data(), static_cast<size_t>(length));
    } while (count < 0 && errno == EINTR);

    if (count < 0) return -errno;
    if (count == 0) return 0;
    env->SetByteArrayRegion(buffer, offset, static_cast<jsize>(count), local.data());
    return static_cast<jint>(count);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zhousl_aether_runtime_AetherPty_nativeWrite(
    JNIEnv* env,
    jobject,
    jint fd,
    jbyteArray buffer,
    jint offset,
    jint length
) {
    if (fd < 0 || buffer == nullptr || offset < 0 || length <= 0) return -EINVAL;
    const jsize buffer_size = env->GetArrayLength(buffer);
    if (offset + length > buffer_size) return -EINVAL;

    std::vector<jbyte> local(static_cast<size_t>(length));
    env->GetByteArrayRegion(buffer, offset, length, local.data());
    ssize_t count;
    do {
        count = write(fd, local.data(), static_cast<size_t>(length));
    } while (count < 0 && errno == EINTR);

    if (count < 0) return -errno;
    return static_cast<jint>(count);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zhousl_aether_runtime_AetherPty_nativeResize(
    JNIEnv*,
    jobject,
    jint fd,
    jint rows,
    jint columns
) {
    if (fd < 0) return -EINVAL;
    winsize window_size{};
    window_size.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    window_size.ws_col = static_cast<unsigned short>(columns > 0 ? columns : 80);
    if (ioctl(fd, TIOCSWINSZ, &window_size) != 0) return -errno;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zhousl_aether_runtime_AetherPty_nativeClose(
    JNIEnv*,
    jobject,
    jint fd,
    jint pid
) {
    if (fd >= 0) {
        close(fd);
    }
    if (pid > 0) {
        kill(static_cast<pid_t>(pid), SIGHUP);
        int status = 0;
        waitpid(static_cast<pid_t>(pid), &status, WNOHANG);
    }
    return 0;
}
