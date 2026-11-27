package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"unsafe"
)

func fail(err error) {
	msg, _ := syscall.UTF16PtrFromString("Estrai tutta la cartella dello ZIP e riprova.\n" + err.Error())
	title, _ := syscall.UTF16PtrFromString("AmpyLink Fusion")
	syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(msg)), uintptr(unsafe.Pointer(title)), 16)
	os.Exit(1)
}

func main() {
	path, err := os.Executable()
	if err != nil {
		fail(err)
	}
	root := filepath.Dir(path)
	cmd := exec.Command(filepath.Join(root, "runtime", "bin", "javaw.exe"), "-jar", filepath.Join(root, "lib", "orb.jar"))
	cmd.Dir = root
	if err := cmd.Run(); err != nil {
		fail(err)
	}
}
